# Proposal

## Why

A senior Java/Spring review of the engine (2026-11-24) found that several checks at the engine's edge can be bypassed or are missing. The review used the `spring-security-review`, `java-supply-chain-security` and `null-safety` checklists from spring-boot-skills. Each finding below was reproduced against the built jar or the engine's classes:

- `InboundKeyFilter` and `IntegrationGate` decide on the raw `getRequestURI()`, but Spring MVC and Camel platform-http route on the decoded path with `;` parameters removed. With `INBOUND_API_KEY` set, `/%61dmin/api/integrations` and `/admin;x/api/integrations` answer 200 without a key; a draft was created this way. `/api/v1/%69ntegrations/<slug>` reaches the route. The same variants also skip the gate's 415/413 checks and its in-flight count (R7).
- `OpenApiImporter` resolves external `$ref`s without limits. A relative `$ref` reads files from the working directory (in the image, `../config/...`), and an `http://` `$ref` makes the engine fetch any host. The WSDL path already limits fetches to the contract's own origin.
- The XML and SOAP request writers don't check element names. A key such as `x/><Admin>true</Admin><y`, sent through a whole-object or whole-list link, is written out as extra elements.
- A file's caller-supplied `contentType` goes into the outbound multipart part headers unchanged, so a CR/LF in it can forge headers or parts. `fileName` is already sanitised.
- Body size is enforced only through `Content-Length`, and only for non-JSON bodies. JSON and chunked bodies are read whole before any limit applies. Contract, WSDL and XSD fetches by URL have no size limit either.

## What Changes

- The key filter and the gate decide on the normalised path: decoded, with `;` parameters removed, the same path the router sees. When a key is set, the key filter denies by default: every path needs the key except an explicit allowlist (console static files, `/`, the published OpenAPI documents).
- OpenAPI import resolves an external `$ref` only from the contract's own origin (URL import), and never from the local file system. Any other reference is refused with a clear import problem. The importer checks the references itself before parsing, because swagger-parser ignores its safe-URL options for Swagger 2.0.
- Element names written to XML and SOAP bodies must be valid XML names (Unicode letters allowed). A request that would write any other name is refused with 400 before any outbound call.
- `Blobs.check` refuses control characters in `contentType` and `fileName`.
- The gate reads every integration call's body up to its limit before admitting the call, for every content type and also when chunked. A new `mapper.max-body-bytes` setting (default 10 MiB) sets the limit for non-file bodies. Contract, WSDL and XSD fetches by URL are capped at the ZIP import's byte limit, and the number of schemas per import is capped.

The error JSON shape (`error`, `integration`, `detail`, `problems`) and the stored config files do not change.

**Ordering:** `fix-publish-and-draft-consistency` fixes the NPE that currently stops whole-object links from publishing. That fix makes the XML name injection above reachable, so this change must land first or together with it.

Out of scope: console authentication beyond the shared key, a separate key per consumer, TLS, and an outbound host allowlist for connections.

## Capabilities

### New Capabilities

- `inbound-boundary`: what the engine accepts from callers and from imported contracts, and what it refuses before any work starts.

### Modified Capabilities

None.

## Impact

- Engine: `web/InboundKeyFilter`, `web/IntegrationGate`, a new `web/RequestPaths`, `contract/OpenApiImporter`, `contract/ContractImporter`, `format/XmlFormat`, `format/SoapEnvelope`, `format/Blobs` and `format/Multipart`, each with unit tests alongside.
- `README.md`: the key's allowlist, `mapper.max-body-bytes` and the `$ref` rule.
- PathchbayTestAndMock: new end-to-end tests for a `;` path at the gate, a chunked over-limit body, a refused `$ref` and a CR/LF `contentType`. The key bypass and the bad element name are covered by engine tests (the test Patchbay runs without a key). The existing tests must still pass.
- No new dependencies.
