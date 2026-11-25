# Proposal

## Why

Patchbay runs inside an internal Docker network, next to the services that call it (README, "Run it inside an internal network"). In that setting the risks are the containers sharing the network and the browsers of the people who use the console. A browser can carry a request from any web page into the internal network.

A security review of the engine (2026-11-25) looked at what `harden-inbound-boundary` left out: the admin API, the console and the addresses the engine calls. Each finding is marked **reproduced** (against a jar built from `aacda82`, empty config folder, no `INBOUND_API_KEY`) or **code reading** (not yet shown by a test).

1. **Any website can make changes through the admin API (reproduced).** The admin endpoints take `@RequestBody String` or `byte[]` without a `consumes`, so a `text/plain` POST is accepted, and a page on any site can send one without a CORS preflight. `POST /admin/api/integrations` with `Content-Type: text/plain` and `Origin: http://evil.example` answered 200 and created a draft. A POST without a body, such as `.../publish`, also reached the publisher from that origin. Once the admin API uses Basic auth, this matters even more: the browser attaches cached Basic credentials to such cross-site requests.
2. **A base URL can be any string (code reading).** `Packages.importZip` accepts a version file whose `target` has a literal `baseUrl` and no connection, and only warns about it. A draft (`services/<slug>.json`) with a literal `baseUrl` gets no warning at all, and `Connections.resolve` passes that value to `POST .../publish`. `Connections.check` runs only when a connection is saved. A package that adds version `9.0.0` to an existing slug with `baseUrl: https://attacker.example`, followed by an activation, makes the live route send `MAPPER_SECRET_<SLUG>` to that host. The same unchecked value becomes the Camel endpoint in `.to("{{baseUrl}}?...")`, with the `file`, `bean`, `controlbus` and `xslt` components on the class path. What a non-http scheme or a `{{...}}` placeholder does there has not been tested.
3. **Admin bodies have no size limit (reproduced).** A 400 MB body to `POST /admin/api/import` threw `OutOfMemoryError: Java heap space` at `-Xmx256m`. The Docker image runs with `-XX:+ExitOnOutOfMemoryError`, so there one request stops the engine.
4. **One key opens everything (code reading).** `INBOUND_API_KEY` is the key for both integration callers and the admin API. Every service that calls an integration can therefore also change connections, publish, export packages and read the call log with its payloads. Without the key, every container in the network has all of this. The slice plan intends "one admin login from env".
5. **The console page has no browser protections (reproduced).** `GET /` has no `Content-Security-Policy`, no `X-Frame-Options` / `frame-ancestors` and no `X-Content-Type-Options`, so a page in another tab can frame the console for clickjacking.

Accepted for the internal network model, so not changed here:

- **Any `Host` is served (reproduced).** DNS rebinding gave full admin access only because the admin API had no login. With Basic auth, the rebinding page is a different origin to the browser, so it gets no cached credentials and does not know the password.
- **A package can point an integration at another host (code reading, finding 2).** Whoever prepares and imports packages and configuration is responsible for checking their connections and addresses (user decision, 2026-11-25), so package import keeps accepting a version or draft with a literal base URL, with today's warning.
- **Contract import by URL reaches internal hosts (reproduced).** It fetches any host, follows redirects, and the errors reveal open ports and the top-level field names of a JSON answer. Only a logged-in admin can start an import, and an admin can already point a connection at any internal host.

Not found: no XXE (all parsing goes through `SafeXml`), no path traversal (slugs are checked against `[a-z0-9]+(-[a-z0-9]+)*`, package paths are allowlisted), and secrets are masked in the Test drawer and the call log. `npm audit` reports no vulnerabilities in the console. Maven dependencies have not been scanned.

## What Changes

- **Admin login (Basic auth).** The console's page and files and everything under `/admin/` need HTTP Basic auth. When there are no credentials, the engine answers 401 with `WWW-Authenticate: Basic realm="Patchbay"`, and the browser shows its own login dialog.
- **Where the login comes from.** `MAPPER_ADMIN_USER` and `MAPPER_ADMIN_PASSWORD` win when both are set. Otherwise the login comes from `<config>/secrets/console/login.json`, which holds the user name and a PBKDF2 hash of the password, never the password itself.
- **First start sets up the login.** With no login in either place, the engine writes a random one-time setup code to its log at every start. The console then shows a "Create admin login" page, which asks for that code, a user name and a password. Until the page is completed, the only admin endpoints that answer are setup's own. The code stops working after its first successful use. To reset the login, delete `secrets/console/login.json` and restart.
- **BREAKING:** `INBOUND_API_KEY` covers integration calls only and no longer opens the console or the admin API. The console's key prompt is removed. Scripts calling `/admin/...` must send Basic credentials.
- **BREAKING for admin clients:** admin writes with a body need `application/json`, or `application/zip` for package import. Every admin write, with or without a body, is refused when it is marked `Sec-Fetch-Site: cross-site` or when its `Origin` does not match its `Host`.
- **Base URLs are checked when a route is built.** Every base URL must pass the connection rule (an absolute http(s) URL with a host, no user info, fragment or placeholder) before a route is built from it, whatever its source: a connection file, a `MAPPER_URL_*` variable, or the literal `target.baseUrl` of a draft or version. Before this change, such a value went into the Camel endpoint unchecked. Literal base URLs stay allowed, in drafts, versions and packages.
- **Admin bodies are capped.** The limit is `mapper.max-body-bytes` for JSON, with room on top for a contract's base64 ZIP and a Test call's base64 file, and the package limit (50 MB) for package import. The filter reads each body up to its limit before any controller runs, and a larger one gets 413.
- **Browser security headers.** The console and the admin API send `Content-Security-Policy` (`default-src 'self'`, `frame-ancestors 'none'`), `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`.

The error JSON keeps its fields. Stored integration, contract and version files do not change; the only new file is `secrets/console/login.json`.

Out of scope: viewer and editor roles, and the console on its own port 8081 (the slice keeps "one admin login from env"); changing the password from the console; logout (browsers keep Basic credentials until they close); lockout after failed logins; TLS, which a reverse proxy adds; and the two accepted findings above.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `inbound-boundary`:
  - Adds the admin login and its first-start setup, the cross-site rules and the response headers.
  - Changes "The inbound key applies to the path the router sees" so the key no longer covers the console and the admin API.
  - Changes "Body size is limited for every request" so it covers admin bodies too. It was built for integration calls only.
- `integration-publishing`: the base URL rule applies to every source of a base URL, not only to saving a connection.

## Impact

- Engine:
  - `web/InboundKeyFilter`: the key no longer covers the console and admin paths.
  - A new `web/AdminRequestFilter`: login, setup, content type, `Origin`, body limits and headers.
  - A new `config/AdminLogin`: environment or file, the PBKDF2 hash and the setup code.
  - The admin controllers: `consumes` on every mapping that takes a body.
  - `config/Connections` and `runtime/IntegrationPublisher`: the base URL rule.
  - Each of these has tests alongside.
- Console: a "Create admin login" page shown when the engine says setup is needed. The key prompt is removed for admin calls, and every write is checked to send a content type.
- `README.md`: the admin login variables, first-start setup and reset, `INBOUND_API_KEY` covering integration calls only, the admin body limit and an updated "Security in short".
- Compatibility, **BREAKING**:
  - Every deployment needs an admin login before the console works, either through the setup page or the two variables.
  - Admin scripts must send Basic credentials, and a content type with any body.
  - The engine API tests and PathchbayTestAndMock admin calls have to be changed.
  - An existing version file whose literal base URL is not a valid http(s) URL is marked broken until it is fixed.
- No new dependencies: PBKDF2 and `SecureRandom` come from the JDK.
