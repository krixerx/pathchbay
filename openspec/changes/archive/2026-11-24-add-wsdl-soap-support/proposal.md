# Proposal

## Why

Patchbay can only call services described by OpenAPI or Swagger, so a service that is offered only as SOAP with a WSDL contract still needs a hand-written integration. The spec lists WSDL import as FR-002 and SOAP as roadmap phase 2; the slice plan deferred it until someone asked, and now it has been asked for.

## What Changes

- Import a WSDL 1.1 contract, with the XSD schemas it imports or includes, by URL, as a single uploaded file or as a ZIP. Its SOAP operations appear in the contract's operation list next to REST operations, with their request and response bodies as the same field trees the board already uses.
- Choose a SOAP operation for an integration. The target then records the SOAP version, the SOAPAction, the body element and the XML namespaces the operation needs; the base URL comes from the connection, prefilled from the WSDL's service address.
- Map Input to the SOAP request body and the SOAP response body to Output on the existing board. Mapping stays JSON to JSON.
- At runtime, Patchbay wraps the mapped request in a SOAP 1.1 or 1.2 envelope with correct namespaces, POSTs it with the right Content-Type and SOAPAction, unwraps the response envelope and maps the body to the caller's Output.
- A SOAP fault reaches the caller as a JSON problem with the fault's code, reason and detail, and keeps the remote HTTP status with `X-Integration-Error: remote`.
- Outbound HTTP Basic authentication, which SOAP services commonly use, is added as an integration auth type for every integration, not only SOAP ones.
- WSDL contracts with their XSD files travel in packages like Swagger configurations do.
- Callers are unaffected in form: a SOAP integration is published as an ordinary REST endpoint that takes a JSON body by POST and appears in `/api/openapi.json`.
- The engine reads WSDL and XSD with DTDs and external entities disabled, and follows `import`/`include` locations only inside the uploaded ZIP or on the same scheme, host and port as the contract URL.

Out of scope for this change, listed as later work: WSDL 2.0, RPC style (rpc/literal and rpc/encoded), SOAP headers in mappings, WS-Security (UsernameToken, signatures), MTOM/XOP attachments, callers sending XML or SOAP, and publishing an integration as a SOAP service.

## Capabilities

### New Capabilities

- `wsdl-contract-import`: importing WSDL 1.1 contracts and their XSDs, normalizing SOAP operations into field trees, safe parsing rules, storage and package portability of multi-file contracts, and how SOAP operations appear when choosing an operation.
- `soap-outbound-call`: calling a SOAP operation from a published integration: envelope building with namespaces, SOAP 1.1 and 1.2 transport headers, reading the response envelope into the Output, SOAP fault handling, and outbound Basic authentication.

### Modified Capabilities

None. The project has no specs under `openspec/specs/` yet; the existing XML behaviour of D-24 is unchanged and SOAP behaviour is added beside it.

## Impact

- Engine (`engine/src/main/java/integrationmapper/engine`): contract import and storage (`ContractImporter`, `Contract`, `ContractApiController`), the operation choice and prefill, the target and auth model (`ServiceDefinition`), XML reading and writing (`XmlFormat` or a SOAP-specific sibling), the outbound and response steps in `IntegrationProcessors` and `CompiledIntegration`, packages (`Packages`, which matches only `.openapi.` sources today), the mock generator and the test runner.
- Console (`engine/console`): contract import accepts `.wsdl` and `.zip`, the operation list shows a SOAP badge, the request format switch is fixed to SOAP for a SOAP operation, Basic auth in the integration's auth settings.
- Dependencies: a WSDL/XSD parsing library may be added; it must be OSI-approved and Apache-2.0 compatible (NFR-009). No SOAP runtime stack is required (see design.md).
- Docs: `engine/README.md` (SOAP section, remove SOAP from the "not yet" list), `README.md`, a new decision D-30 in `docs/slice-plan.md`.
- End-to-end tests in the sibling project PathchbayTestAndMock: a WSDL served by WireMock, SOAP stubs, SOAP integrations in the test package, and SOAP-aware checks in `SetupTest`.
- Existing integrations, version files and packages keep working unchanged.
