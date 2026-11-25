## ADDED Requirements

### Requirement: Every base URL is checked before a route is built
The engine SHALL apply the base URL rule (absolute http(s) URL with a host, no user info, no fragment, no placeholder) to the address an integration calls, whatever its source: a connection file, a `MAPPER_URL_*` variable, or a literal `target.baseUrl` in a draft or version. A route SHALL NOT be built from an address that fails the rule. A literal base URL that passes the rule SHALL stay allowed.

#### Scenario: Non-http scheme in a stored version
- **WHEN** a version file in the config folder has `target.baseUrl` `file:/config/secrets`
- **THEN** activating or loading it fails with a problem naming the base URL, the integration is reported broken, and no route is created

#### Scenario: Bad variable value
- **WHEN** `MAPPER_URL_BOOKS` is `http://{{env:INBOUND_API_KEY}}.example`
- **THEN** publishing an integration on connection `books` is refused as not a valid URL

#### Scenario: Literal address from an older draft
- **WHEN** a draft without a connection has `target.baseUrl` `https://api.books.example` and is published
- **THEN** the integration is published and calls that address
