# Proposal

## Why

`Contract` and `Contract.Operation` are one record each for every contract kind. A WSDL contract is told apart by an optional `sourceKind` and carries `files`; a SOAP operation is told apart by an optional `protocol` and carries `soap` and `unsupported`, while still holding a REST-shaped `method`, `path`, `parameters` and `responseStatus` it doesn't use. Callers ask `isWsdl()` and `isSoap()` and read whichever optional fields apply. Every further kind (API Blueprint is in the spec, others may follow) would add more optional fields to the same records. Separate types per kind make each one say what it holds, and let the compiler point at every caller that has to handle a new kind.

## What Changes

- `Contract` becomes a sealed interface with two records: `OpenApiContract` (OpenAPI 3 and converted Swagger 2.0) and `WsdlContract` (WSDL 1.1, with its `files`).
- `Contract.Operation` becomes a sealed interface `Operation` with two records: `RestOperation` (`method`, `path`, `parameters`, `responseStatus`) and `SoapOperation` (`soap`, `unsupported`).
- `Contract.Parameter` becomes `RestOperation.Parameter`, since only REST operations have parameters.
- Callers (`ContractApiController`, `MappingApiController`, `TestRunner`, `Prefill`, `Packages`, the two importers) switch on the type instead of calling `isWsdl()` / `isSoap()`, which are removed.
- The JSON stays exactly as it is: the existing `sourceKind` and `protocol` fields pick the type when reading, and a missing one means OpenAPI or REST. Stored contract files, exported packages and the console need no change and no migration.

Not **BREAKING** for callers, the console, config folders or packages.

Out of scope: adding a new contract kind, changing the JSON layout, the console's TypeScript model, and `ServiceDefinition`'s own `soap` target block.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None. Behaviour and the stored format are unchanged; the change sets `skip_specs: true`.

## Impact

- Engine `model` package: `Contract.java` is replaced by `Contract`, `OpenApiContract`, `WsdlContract`, `Operation`, `RestOperation` and `SoapOperation`.
- Engine callers listed above, and the tests that build contracts or operations (`PrefillTest`, `ContractImporterTest` and others).
- `CLAUDE.md` (the stored-record Jackson note covers the new types too).
- End-to-end tests in PathchbayTestAndMock run unchanged and must still pass.
- No dependency, API or configuration changes.
