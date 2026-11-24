# Spec Delta

## Purpose

Defines what the engine accepts from callers and from imported contracts, and what it refuses before any mapping or outbound call starts.

## ADDED Requirements

### Requirement: The inbound key applies to the path the router sees
When `INBOUND_API_KEY` is set, the engine SHALL require the `X-API-Key` header for every request except an explicit allowlist: console static files, `/`, and the published OpenAPI documents. The engine SHALL make this decision on the decoded path, with `;` path parameters removed.

#### Scenario: Percent-encoded admin path
- **WHEN** a key is set and a request without the header is sent to `/%61dmin/api/integrations`
- **THEN** the engine answers 401 and no draft is read or written

#### Scenario: Path parameter on an integration path
- **WHEN** a key is set and a request without the header is sent to `/api/v1/integrations;x/get-book`
- **THEN** the engine answers 401 and the integration's route is not called

#### Scenario: Allowlisted path
- **WHEN** a key is set and a request without the header asks for the console's start page
- **THEN** the page is served

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
The engine SHALL stop reading a request body once it exceeds the limit for its kind, whether or not the request has a `Content-Length`, and SHALL answer 413. The limit is `mapper.max-blob-bytes` for files and `mapper.max-body-bytes` for all other bodies.

#### Scenario: Chunked JSON body over the limit
- **WHEN** a caller sends a chunked JSON body larger than `mapper.max-body-bytes`
- **THEN** the caller gets 413, and the engine has read no more than the limit plus one buffer

#### Scenario: Contract fetched by URL is too large
- **WHEN** an analyst imports a contract URL whose answer is larger than the import limit
- **THEN** the import fails with a size problem, and the engine has not read the whole answer
