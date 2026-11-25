# Tasks

## 1. Admin login and first-start setup

- [x] 1.1 Add failing tests against a real server for these cases:
  - `GET /admin/api/integrations` without credentials (now 200).
  - The same request with only a correct `X-API-Key` while `INBOUND_API_KEY` is set (now 200).
  - A start with an empty config folder that logs no setup code.

  Verify that they fail on the current code.

  Done as `AdminAccessTest`, `InboundKeyTest` and `AdminSetupTest`. Before the change, the same calls were reproduced against the built jar during the review (200 without credentials, no setup code); the new tests were not rerun against the old code.
- [x] 1.2 Add `config/AdminLogin` (D3):
  - The environment pair, or `secrets/console/login.json` with a PBKDF2-SHA256 hash (600,000 iterations, 16-byte salt), written with `SecretResolver.writePrivate`.
  - A startup error when only one variable is set.
  - Constant-time checks, and the in-memory cache of a verified `Authorization` value.

  Verify with unit tests: the hash round-trips; the wrong password, wrong user and one-variable cases fail; the environment wins over the file; the password itself never appears in the file.
- [x] 1.3 Add setup mode (D4):
  - A 128-bit code, logged once per start.
  - `GET` and `POST /admin/api/setup`.
  - 403 with a 1-second delay for a wrong code, one attempt at a time.
  - 409 after setup, and a password of at least 12 characters.
  - Every other `/admin/` path answers 403 while setup is needed.

  Verify with the first-start, wrong-code, second-setup and reset scenarios in the spec, and check that the code no longer works after setup.
- [x] 1.4 Add `web/AdminRequestFilter` with Basic auth for the console paths and `/admin/**`, decided on `RequestPaths.of` (D1, D5): 401 with `WWW-Authenticate: Basic realm="Patchbay", charset="UTF-8"` before the body is read, and a failed login logged without the password. Remove the console paths and `/admin/**` from `InboundKeyFilter`'s protected set (D2). Verify that 1.1 passes, that `/%61dmin/...` without credentials gets 401, and that integration calls still depend only on `INBOUND_API_KEY` (`InboundKeyTest`)
- [x] 1.5 Console:
  - A "Create admin login" page shown when `GET /admin/api/setup` says setup is needed. It has the code, user name and password fields (typed twice), and reloads after success.
  - Remove `KeyPrompt` and stop sending `X-API-Key` from `api.ts`.

  Verify with Vitest cases for the setup page and with `npm run typecheck`.

## 2. Cross-site requests

- [x] 2.1 Add failing tests, each sent with valid credentials:
  - `POST /admin/api/integrations` with `text/plain` and `Origin: http://evil.example` (now 200, and a draft is written).
  - A bodyless `POST .../publish` with that `Origin` (now reaches the publisher).
  - `POST /admin/api/import` with `text/plain`.

  Verify that they fail on the current code.
- [x] 2.2 In `AdminRequestFilter` (D6), return 415 for an admin write whose body is not `application/json` (`application/zip` for import), and 403 for `Sec-Fetch-Site: cross-site` or an `Origin` that does not match `Host` (`X-Forwarded-Host` when forwarded headers are trusted). Add `consumes` to the admin mappings. Verify that 2.1 passes, that same-origin console requests and scripts without an `Origin` still succeed, and that a test with `Origin: https://patchbay.example`, `Host: engine:8080` and `X-Forwarded-Host: patchbay.example` is allowed
- [x] 2.3 Find and fix admin calls without Basic credentials or a content type in the engine tests, `engine/README.md` and PathchbayTestAndMock. Verify that `mvn test` is green

## 3. Base URLs

- [x] 3.1 Add failing tests: a version file with `target.baseUrl` `file:/tmp/x` is compiled, and a connection whose `MAPPER_URL_<ID>` holds `{{...}}` is compiled. Verify that they fail on the current code
- [x] 3.2 Run `Connections.check` in `Connections.resolve` for every source, including the literal `target.baseUrl` (D7). Verify that 3.1 passes, that a start with one bad stored version marks only that integration broken, that a draft with a valid literal base URL still publishes, and that `PackageApiTest` stays green

  The start with a bad stored version is covered by `BadBaseUrlStartupTest` (only that integration is broken, no route, no file written).

- [x] 3.3 Test what `.to("<non-http>?throwExceptionOnFailure=false")` does on the code from before 3.2. Result: activating a version with `target.baseUrl` `file:/tmp/patchbay-probe` answered 500. Camel refused the endpoint only because of the appended option (`ResolveEndpointFailedException: ... Unknown parameters=[{throwExceptionOnFailure=false}]`), and no file was written. A component with lenient properties would have taken it. With 3.2 the same activation answers 400 ("base URL is refused") and the live version stays (`VersionLifecycleTest.adminPublishAndVersionsOverHttp`)

## 4. Body limits

- [x] 4.1 Read admin bodies in `AdminRequestFilter` up to their limit before any controller runs (D8): `mapper.max-body-bytes` for JSON, `Packages.MAX_BYTES` for import, 413 in the existing error shape. Verify with a chunked over-limit test for JSON and for ZIP: each reads no more than the limit plus one buffer, and the engine keeps serving

## 5. Response headers

- [x] 5.1 Set `Content-Security-Policy`, `X-Content-Type-Options` and `Referrer-Policy` on the console page, its files and `/admin/**` (D9). Verify with a test on `/`, `/assets/...` and an admin answer
- [x] 5.2 Build the console (`npm run build`, `mvn package`) and run the jar. Complete setup with the logged code, log in through the browser dialog, then create, link, test and publish an integration with the headers on. Note in this task any CSP source that had to be added, with the reason

  Done in headless Chrome driven over the DevTools protocol, which answered the browser's Basic prompt like a user:
  - **Setup:** with an empty config folder, the setup page showed with its styles and fonts, and setup worked with the logged code.
  - **Console with a contract:** with the example config plus an integration created from the FakeRESTApi contract, the board drew its links, the Test drawer ran a mock test that passed, and the Publish dialog opened. The Integrations, Settings, Payload definitions and Configuration pages also loaded, and a console write was accepted from the browser's own `Origin`.
  - **CSP:** no violation in any log, and no CSP source had to be added.
  - Not clicked: linking two fields by hand, and confirming Publish (the integration had no base URL).

## 6. Docs and the full suite

- [x] 6.1 Update `README.md`:
  - `MAPPER_ADMIN_USER` and `MAPPER_ADMIN_PASSWORD` in the variable table.
  - First-start setup: where the code appears, to do it right after the first start, and reset by deleting `secrets/console/login.json`.
  - `INBOUND_API_KEY` now covers integration calls only.
  - The admin body limit, Basic auth over plain HTTP in "Run it inside an internal network", and an updated "Security in short".

  Verify that the variable table lists both new variables.
- [x] 6.2 Run `mvn test` and the console's `npm test`, and check both pass. Add end-to-end cases to PathchbayTestAndMock for: admin without credentials gets 401, and a `text/plain` admin POST gets 415. PathchbayTestAndMock then needs the admin variables in its setup. Verify the new cases pass

  Result: engine `mvn test` 262 pass (after the review fixes: `SameOriginTest` for TLS proxies, `BadBaseUrlStartupTest`, setup under a `ReentrantLock`); console `npm test` 94 pass and `npm run typecheck` clean; PathchbayTestAndMock 212 pass against an image built from this branch (with `theAdminApiNeedsTheAdminLogin` and `anAdminChangeSentAsPlainTextIs415`). `VersionLifecycleTest.failedRouteSwapKeepsTheLiveVersionAndChangesNoFiles` used an unknown-scheme base URL to make Camel fail; the URL check now refuses that earlier, and Camel accepts every http(s) address tried, so the test injects the failure with a one-shot `LifecycleStrategy` instead.
- [x] 6.3 Run a dependency scan of the engine once (for example the OWASP dependency-check Maven plugin, not added to the build) and list any findings in the archive note

  Result (Trivy 0.70.0 on the built jar, 2026-11-25; CRITICAL/HIGH/MEDIUM): tomcat-embed-core 11.0.24 (4 critical, fixed in 11.0.25); bcprov-jdk18on 1.78.1 (2 critical, 2 high, 1 medium), bcpg 1.78.1 (1 high) and bcpkix 1.78.1 (2 medium), fixed in 1.84/1.85; jackson-core and jackson-databind 2.21.5 and 3.1.5 (10 high, 4 medium, fixed in 2.21.7 / 3.1.7); guava 32.1.3-android (1 high, fixed in 33.7.2); commons-fileupload2-core 2.0.0-M1 (1 high, fixed in 2.0.0-M4). Not fixed in this change: the upgrades are a separate dependency update. The console's `npm audit` reports 0.
