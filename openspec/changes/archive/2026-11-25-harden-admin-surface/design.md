# Design

## Context

See proposal.md for the findings and the internal network model. Today a request passes `RequestIdFilter` and then `InboundKeyFilter`, which uses one key, denies by default and decides on `RequestPaths.of`. After that it goes either to Spring MVC (`/admin/**`, the console's files, the OpenAPI documents) or to Camel platform-http behind `IntegrationGate` (`/api/v1/integrations/**`). The admin controllers read bodies as `String` or `byte[]` and parse them with `Json.MAPPER`, so Spring never checks the content type. The console sends `X-API-Key` from `sessionStorage` (`KeyPrompt`). Secret files are written by `SecretResolver.writePrivate` with owner-only permissions. A route is compiled in `IntegrationPublisher.compile` with a base URL from `Connections.resolve`. Only the console's connection form calls `Connections.check`.

## Goals / Non-Goals

**Goals:** one admin login that is separate from the callers' key, a safe first start without a preset password, no drive-by changes from other web pages, admin bodies that cannot exhaust the heap, and the base URL rule holding on every path into a route.

**Non-Goals:** Spring Security, sessions or login forms beyond setup; users and roles; password change in the console; CORS for other origins; outbound host allowlists; private-range blocking for contract fetches.

## Decisions

**D1. One admin filter, not Spring Security.** A new `AdminRequestFilter`, ordered after `RequestIdFilter`, covers the console paths and `/admin/**`, decided on `RequestPaths.of`. It checks, in this order:
1. The login, or the setup state.
2. `Origin` and `Sec-Fetch-Site`.
3. The content type.

It also sets the response headers. Spring Security would bring sessions, its own CSRF tokens, a login page and its own path matching. That is a lot to configure for one Basic login, and its defaults do not fit a hash-router console. The `consumes` attributes added to the admin mappings give a clear 415 even if the filter list drifts.

**D2. `INBOUND_API_KEY` leaves admin paths.** `InboundKeyFilter` adds the console paths and `/admin/**` to its open list, so integration callers' key and the admin login never overlap. The console stops sending `X-API-Key`, and `KeyPrompt` is removed. Test calls with `real: true` still go through the admin API. They call the external system directly, as today, not through the integration path, so they do not need the key.

**D3. Where the login comes from (`config/AdminLogin`).**
1. **Environment:** when both `MAPPER_ADMIN_USER` and `MAPPER_ADMIN_PASSWORD` are set, they are the login. This follows ADR-004: environment values win.
2. **File:** otherwise `secrets/console/login.json`, holding `{"user": "...", "hash": "pbkdf2-sha256$<iterations>$<salt b64>$<hash b64>"}`. The JDK's `PBKDF2WithHmacSHA256` is used with 600,000 iterations and a 16-byte salt from `SecureRandom`, and the file is written with `SecretResolver.writePrivate`.
3. **Neither:** setup mode.

Only one variable set is a startup error, so a typo cannot silently fall back to the file. PBKDF2 at this cost takes about 0.3 s, too slow for every console request. After a successful check, the engine therefore keeps a SHA-256 of the exact `Authorization` value in memory and compares later requests against it in constant time. The cache is cleared when the login changes. Alternative: bcrypt from spring-security-crypto. Rejected, because it is a new dependency for one function the JDK already has.

**D4. First-start setup with a logged code.** In setup mode, each start generates a 128-bit code from `SecureRandom`, shown as 26 base32 characters, and logs it once at WARN in this form: `Create the admin login in the console with setup code XXXX (valid until it is used or the engine restarts)`. Only people who can read the container log can complete setup. In Docker that is the operator, so the race "first person to open the console" goes away.

The setup API has two endpoints:
- `GET /admin/api/setup` returns `{"needed": true|false}`.
- `POST /admin/api/setup` takes `{"code", "user", "password"}` as `application/json` and passes the same Origin check.

A wrong code answers 403 after a fixed 1-second delay, with one attempt at a time (a lock), which limits guessing to about one per second. At 128 bits, that is enough. Setup runs under a fair `ReentrantLock`, so the first valid request wins and later ones get 409. Not `synchronized`: on Java 21 (the image's runtime) a virtual thread that sleeps or waits inside a monitor pins its carrier thread, so a burst of wrong codes could stall the engine. Afterwards the code is discarded, and the console reloads so the browser asks for the new login.

Alternatives considered:
- **Generated password in the log (Spring Boot style).** Rejected: the password stays in the log for as long as the logs are kept.
- **Setup allowed only from loopback.** Rejected: in Docker the browser's request reaches the container from the bridge address, not from loopback.

**D5. Browser login dialog.** After setup, a request without valid credentials gets 401 with `WWW-Authenticate: Basic realm="Patchbay", charset="UTF-8"`. The browser asks once and sends the credentials on later requests, including the console's `fetch` calls. The console needs no login code apart from the setup page. In setup mode the console's page loads without a login, `App` asks for `GET /admin/api/setup`, and shows the setup page when setup is needed.

**D6. Two CSRF controls.** Browsers attach cached Basic credentials to cross-site requests, so CSRF protection is now required.
- **Writes with a body:** browsers send a preflight for `application/json` and `application/zip`, and the engine never answers a preflight with `Access-Control-Allow-Origin`.
- **Writes without a body:** publish and activate need no preflight, so the engine refuses `Sec-Fetch-Site: cross-site` and any `Origin` that differs from `Host`. Current browsers send `Origin` on every cross-site POST.

Requests without an `Origin` (curl, scripts) pass. An `Origin` matches when its host and port equal the `Host` header (default ports left out, so a TLS proxy that keeps `Host` needs no setting), or the address Spring resolved, which comes from `X-Forwarded-Host`/`-Proto` when the engine trusts forwarded headers (`server.forward-headers-strategy=native`). The 403 names that setting.

**D7. Check base URLs when a route is compiled.** `Connections.resolve` runs `Connections.check` on every address it returns, including the literal `target.baseUrl` of older drafts and versions, and `IntegrationPublisher.compile` uses it. A bad `MAPPER_URL_*` value or a stored `file:` / `{{...}}` address therefore never becomes a Camel endpoint. Package import and publish do not refuse literal addresses: the person who prepares and imports packages and configuration is responsible for checking their connections and addresses (user decision, 2026-11-25).

**D8. Bounded admin bodies, read in the filter.** `AdminRequestFilter` reads every admin body up to `limit + 1` bytes, as `IntegrationGate` does for integration calls, answers 413 when it is larger, and hands the controllers the buffered body (`IntegrationGate.BufferedBody`). Doing it in the filter covers every admin endpoint, also future ones, without changing the controllers. Limits: `Packages.MAX_BYTES` for package import, `mapper.max-body-bytes` plus a base64 ZIP (`ContractImporter.MAX_ZIP_BYTES`) for contracts, `mapper.max-body-bytes` plus one base64 file (`mapper.max-blob-bytes`) for test calls, and `mapper.max-body-bytes` otherwise. Tomcat's `maxPostSize` was rejected, because it does not cover chunked or non-form bodies.

**D9. Response headers.** The filter sets `Content-Security-Policy: default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'`, `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`. Mantine injects styles at runtime, so `style-src 'self' 'unsafe-inline'` will probably be needed. Task 5.2 settles that against the built console.

## Risks / Trade-offs

- [Anyone who can read the container logs during setup mode can create the login] → that is the operator in the intended setup. The README says to complete setup right after the first start, and that log shipping copies the code too. The code works only until setup is done.
- [Basic credentials are cached by the browser until it closes, and there is no logout] → documented. The CSRF rules in D6 cover cross-site use of the cached login.
- [Basic auth over plain HTTP sends the password readable on the network] → documented with the internal network model. A reverse proxy adds TLS when people reach the console from other machines.
- [A failed-login brute force is not locked out] → failures are logged with the client address, and the README asks for a long password. A lockout is out of scope.
- [A reverse proxy that rewrites `Host` makes every console write fail the Origin check] → D6 uses `X-Forwarded-Host` when forwarded headers are trusted. Documented.
- [A package can still point an integration at another host and its secret] → accepted. Importing a package is an admin action, and the importer checks its addresses; the import report keeps warning about literal addresses.
- [Old version files with a base URL that is not a valid http(s) URL stop loading] → they are marked broken with the reason, and other integrations load.
- [CSP breaks the console] → task 5.2 runs the built console under the header before release.

## Migration Plan

1. After upgrading, the engine starts in setup mode, unless `MAPPER_ADMIN_USER` and `MAPPER_ADMIN_PASSWORD` are set.
2. The operator reads the setup code from `docker logs patchbay` and creates the login in the console.
3. Callers keep using `INBOUND_API_KEY` unchanged.
4. Admin scripts switch from `X-API-Key` to Basic credentials.

Rollback is the previous image. `secrets/console/login.json` is then ignored, and nothing else on disk is rewritten.
