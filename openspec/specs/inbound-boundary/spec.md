# inbound-boundary Specification

## Purpose

Defines what the engine accepts from callers and from imported contracts, and what it refuses before any mapping or outbound call starts.

## Requirements

### Requirement: The inbound key applies to the path the router sees
When `INBOUND_API_KEY` is set, the engine SHALL require the `X-API-Key` header for every request except an explicit allowlist: the console's page and files, every path under `/admin/` (which the admin login protects), the published OpenAPI documents and the health probes. The engine SHALL make this decision on the decoded path, with `;` path parameters removed.

#### Scenario: Percent-encoded admin path
- **WHEN** a key is set and a request without the header and without the admin login is sent to `/%61dmin/api/integrations`
- **THEN** the engine answers 401 and no draft is read or written

#### Scenario: Percent-encoded integration path
- **WHEN** a key is set and a request without the header is sent to `/api/v1/%69ntegrations/get-book`
- **THEN** the engine answers 401 and the integration's route is not called

#### Scenario: Path parameter on an integration path
- **WHEN** a key is set and a request without the header is sent to `/api/v1/integrations;x/get-book`
- **THEN** the engine answers 401 and the integration's route is not called

#### Scenario: Allowlisted path
- **WHEN** a key is set and a request without the header asks for the console's start page
- **THEN** the key is not checked, and the admin login decides

#### Scenario: Key on the admin API
- **WHEN** a key is set and a request to `GET /admin/api/integrations` sends only the right `X-API-Key`
- **THEN** the engine answers 401, because the admin API needs the admin login

### Requirement: OpenAPI references stay within the contract's origin
The engine SHALL resolve an external `$ref` in an OpenAPI or Swagger contract only when the contract was imported by URL and the reference has the same origin. The engine SHALL never resolve a `$ref` to the local file system.

#### Scenario: Relative file reference in pasted text
- **WHEN** an analyst imports contract text that contains `$ref: '../config/connections.json'`
- **THEN** the import reports the reference as refused and no local file is read

#### Scenario: Reference to another host
- **WHEN** a contract imported from `http://api.example/openapi.yaml` contains `$ref: 'http://169.254.169.254/latest'`
- **THEN** the engine does not request that URL and the import reports the reference as refused

### Requirement: Outbound XML element names are valid
The engine SHALL refuse with 400, before any outbound call, a request whose mapped XML or SOAP body would contain an element name that is not a valid XML name.

#### Scenario: Injected element name
- **WHEN** an integration links an Input object as a whole to an XML body and the caller sends the key `x/><Admin>true</Admin><y`
- **THEN** the caller gets 400 and the external system receives nothing

### Requirement: File metadata contains no control characters
The engine SHALL refuse with 400 a file whose `contentType` or `fileName` contains a control character.

#### Scenario: Header injection through contentType
- **WHEN** a caller sends a file with `contentType` `text/plain\r\nContent-Disposition: form-data; name="role"`
- **THEN** the caller gets 400 and the external system receives nothing

### Requirement: Body size is limited for every request
The engine SHALL stop reading a request body once it exceeds the limit for its kind, whether or not the request has a `Content-Length`, and SHALL answer 413. The limit is `mapper.max-blob-bytes` for files, the package size limit for package import, and `mapper.max-body-bytes` for every other body, admin requests included. Bodies that may carry files as base64 (integration calls, contract imports, Test calls) get room for them on top.

#### Scenario: Chunked JSON body over the limit
- **WHEN** a caller sends a chunked JSON body larger than `mapper.max-body-bytes`
- **THEN** the caller gets 413, and the engine has read no more than the limit plus one buffer

#### Scenario: Contract fetched by URL is too large
- **WHEN** an analyst imports a contract URL whose answer is larger than the import limit
- **THEN** the import fails with a size problem, and the engine has not read the whole answer

#### Scenario: Oversized package
- **WHEN** a 400 MB body is sent to `POST /admin/api/import` with the login
- **THEN** the engine answers 413, has read no more than the limit plus one buffer, and keeps serving

#### Scenario: Oversized admin JSON body
- **WHEN** a chunked body larger than `mapper.max-body-bytes` is sent to `PUT /admin/api/definitions/x` with the login
- **THEN** the engine answers 413 and writes nothing

### Requirement: The console and admin API need the admin login
Once an admin login exists, the engine SHALL require HTTP Basic credentials that match it for the console's page and files and for every path under `/admin/`, decided on the path the router sees. A request without matching credentials SHALL get 401 with `WWW-Authenticate: Basic realm="Patchbay"` before its body is read. Credentials SHALL be compared in constant time, and a failed login SHALL be logged without the password.

#### Scenario: Console without credentials
- **WHEN** a login exists and a browser asks for `/` without an `Authorization` header
- **THEN** the engine answers 401 with `WWW-Authenticate: Basic realm="Patchbay"`

#### Scenario: Admin call with the login
- **WHEN** a login exists and `GET /admin/api/integrations` carries matching Basic credentials
- **THEN** the integrations are listed

#### Scenario: Encoded admin path without credentials
- **WHEN** a login exists and a request without credentials is sent to `/%61dmin/api/integrations`
- **THEN** the engine answers 401 and no draft is read

#### Scenario: Integration call is not affected
- **WHEN** a login exists and a caller sends `POST /api/v1/integrations/get-book` without Basic credentials
- **THEN** the call is decided by `INBOUND_API_KEY` alone, as before

### Requirement: The environment's admin login wins
When both `MAPPER_ADMIN_USER` and `MAPPER_ADMIN_PASSWORD` are set, they SHALL be the admin login, and the stored login SHALL be ignored. When only one of them is set, the engine SHALL refuse to start, naming the missing one.

#### Scenario: Environment wins
- **WHEN** `MAPPER_ADMIN_USER` and `MAPPER_ADMIN_PASSWORD` are set and `secrets/console/login.json` holds another login
- **THEN** only the environment's login is accepted, and no setup page is offered

#### Scenario: Only one variable
- **WHEN** `MAPPER_ADMIN_USER` is set and `MAPPER_ADMIN_PASSWORD` is not
- **THEN** the engine does not start, and its log names `MAPPER_ADMIN_PASSWORD`

### Requirement: First start creates the admin login
When the environment gives no login and `secrets/console/login.json` does not exist, the engine SHALL generate a random setup code of at least 128 bits at each start, keep it only in memory and write it to its log. Until a login exists, the console's page and files SHALL be served, the setup status and setup endpoints SHALL answer, and every other `/admin/` path SHALL answer 403, saying that the admin login must be created first.

#### Scenario: First start
- **WHEN** the engine starts with an empty config folder and no admin variables
- **THEN** its log holds a setup code, the console shows "Create admin login", and `GET /admin/api/integrations` answers 403

### Requirement: Setup stores a hashed login once
Setup SHALL require the current code (compared in constant time), a user name and a password of at least 12 characters. It SHALL store the user name and a salted PBKDF2 hash in `secrets/console/login.json`, readable only by the engine's user where the file system supports that, and never the password. A wrong code SHALL get 403 after a delay. After a successful setup, the code SHALL stop working and setup SHALL answer 409. Deleting the file and restarting SHALL start setup again with a new code.

#### Scenario: Setup with the code
- **WHEN** the console sends the logged code, user `admin` and a 16-character password to the setup endpoint
- **THEN** `secrets/console/login.json` holds `admin` and a PBKDF2 hash, the password itself appears in no file or log line, and the admin API now asks for Basic credentials

#### Scenario: Wrong code
- **WHEN** a request to the setup endpoint sends a code that is not the logged one
- **THEN** the engine answers 403, no file is written, and a new attempt is accepted only after a delay

#### Scenario: Second setup
- **WHEN** a login exists and a request to the setup endpoint sends the old code
- **THEN** the engine answers 409 and `secrets/console/login.json` is unchanged

#### Scenario: Reset
- **WHEN** `secrets/console/login.json` is deleted and the engine restarts
- **THEN** a new setup code is logged and the old one is not accepted

### Requirement: Cross-site requests cannot change the configuration
The engine SHALL refuse with 403 a `POST`, `PUT` or `DELETE` under `/admin/`, with or without a body, that carries `Sec-Fetch-Site: cross-site` or an `Origin` whose host and port differ from the request's `Host` (or from `X-Forwarded-Host` when forwarded headers are trusted). The engine SHALL NOT answer a CORS preflight with headers that allow another origin.

#### Scenario: Plain-text form post from another site
- **WHEN** a browser holding the admin login sends `POST /admin/api/integrations` from `Origin: http://evil.example` with `Content-Type: text/plain`
- **THEN** the engine answers 415 or 403 and no draft is written

#### Scenario: Cross-site publish without a body
- **WHEN** a browser holding the admin login sends `POST /admin/api/integrations/get-book/publish` with no body from `Origin: http://evil.example`
- **THEN** the engine answers 403 and no version is written

#### Scenario: Console request
- **WHEN** the console on `http://localhost:8080` sends `POST /admin/api/integrations` with `Content-Type: application/json`, `Origin: http://localhost:8080` and the login
- **THEN** the draft is created

#### Scenario: Script without an Origin
- **WHEN** a script sends `POST /admin/integrations/get-book/publish` with the login, no body and no `Origin`
- **THEN** the integration is published

### Requirement: Admin writes need a JSON or ZIP body type
The engine SHALL refuse with 415 a `POST`, `PUT` or `DELETE` under `/admin/` that has a body whose content type is not `application/json`, or `application/zip` for package import.

#### Scenario: Package import as plain text
- **WHEN** a package ZIP is sent to `POST /admin/api/import` with the login and `Content-Type: text/plain`
- **THEN** the engine answers 415 and writes no file

### Requirement: Console and admin answers carry browser protections
Every answer for the console page, its files and `/admin/**` SHALL carry `Content-Security-Policy` with at least `default-src 'self'` and `frame-ancestors 'none'`, plus `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`.

#### Scenario: Console start page
- **WHEN** a browser loads `/` with the login
- **THEN** the answer has those three headers, and the console still loads and works
