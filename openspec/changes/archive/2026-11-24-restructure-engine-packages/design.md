# Design

## Context

See proposal.md for why. Today the engine is one package, `integrationmapper.engine`, so Java's package-private access is used freely: 12 classes (`Json`, `Blobs`, `XmlFormat`, `SafeXml`, `SoapEnvelope`, `Multipart`, `InputValidator`, `MockGenerator`, `Prefill`, `SampleInference`, `WsdlImporter` and others) and about 100 methods have no access modifier. `EngineApplication` sits in the root package, so Spring Boot's component scan already covers any sub-package. Test resources are loaded by absolute classpath paths (`/contracts/...`, `/wsdl/...`), and nothing refers to engine classes by name in properties, the Dockerfile or logging configuration.

## Goals / Non-Goals

**Goals:**

- A package tells what a class is for, and the packages a class imports show what it depends on.
- The move keeps git history per file (`git mv`, no content rewrite beyond `package`/`import` lines and access modifiers).
- Behaviour, JSON shapes and the HTTP surface stay the same; the engine's own tests and the end-to-end suite in PathchbayTestAndMock pass unchanged.

**Non-Goals:**

- Renaming, splitting or merging classes, or changing any logic.
- Module boundaries enforced by tooling (ArchUnit, Java modules). That can follow once the layout has settled.
- The console (`engine/console`).

## Decisions

### Packages by role

Sub-packages of `integrationmapper.engine`:

| Package | Classes | Role |
|---|---|---|
| (root) | `EngineApplication` | Spring Boot entry point; keeps the component scan rooted above every sub-package |
| `api` | `AdminController`, `ConsoleApiController`, `ContractApiController`, `MappingApiController`, `OpenApiController`, `PackageApiController` | REST controllers |
| `model` | `Contract`, `PayloadDefinition`, `ServiceDefinition`, `SoapTarget`, `Version`, `VersionSnapshot` | Records read from and written to the config folder |
| `web` | `InboundHttpConfig`, `OutboundHttpConfig`, `InboundKeyFilter`, `IntegrationGate` | Spring HTTP configuration and servlet filters |
| `runtime` | `IntegrationRoutes`, `IntegrationProcessors`, `IntegrationRegistry`, `IntegrationPublisher`, `CompiledIntegration`, `ConfigLoader`, `CallLog`, `InputValidator`, `TestRunner` | Publishing, loading and serving integrations |
| `mapping` | `LinkRules`, `ScriptGenerator`, `Prefill`, `SampleInference`, `MockGenerator` | Links to DataSonnet, definitions and samples derived from contracts |
| `contract` | `ContractImporter`, `WsdlImporter` | Importing OpenAPI, Swagger and WSDL contracts |
| `config` | `ConfigStore`, `Connections`, `SecretResolver`, `PayloadLibrary`, `Packages` | The config folder, connections, secrets and packages |
| `format` | `Json`, `XmlFormat`, `SafeXml`, `SoapEnvelope`, `Multipart`, `Blobs` | Wire formats |

Alternative considered: packages by feature (`contract`, `integration`, `packaging`, `console`), each holding its own controller, records and services. That groups code that changes together better, but the request was to separate controllers from models, and most classes here serve several features (`ServiceDefinition`, `Json`, `ConfigStore`), so feature packages would still need a large shared package. Role packages are the smaller, clearer step.

Placement calls that could go either way:

- `CompiledIntegration` goes to `runtime`, not `model`: it holds compiled scripts and runs them, and is never stored as JSON.
- `IntegrationGate` goes to `web`: it is a servlet filter, although it serves the runtime's route swap (R7). It depends on `runtime.IntegrationRegistry`, which is fine.
- `PayloadLibrary` goes to `config`: it is the loaded `entities/` folder, as `Connections` is the loaded connections.
- Nested types (for example the request records inside controllers) stay nested; only top-level types move.

### Tests follow the class under test

| Package | Tests |
|---|---|
| `api` | `ConsoleApiTest`, `ContractApiTest`, `MappingApiTest`, `OpenApiSchemaTest`, `PackageApiTest`, `SoapContractApiTest`, `XmlFormatsApiTest` |
| `model` | `PayloadDefinitionTest` |
| `web` | `InboundKeyTest` |
| `runtime` | `CallLogTest`, `EngineSkeletonTest`, `HeaderDefinitionsTest`, `ListMappingTest`, `SoapIntegrationTest`, `StartupImportTest`, `VersionLifecycleTest`, `XmlIntegrationTest` |
| `mapping` | `LinkCasesTest`, `PrefillTest`, `SampleInferenceTest`, `ScriptGeneratorTest` |
| `contract` | `ContractImporterTest`, `WsdlImporterTest` |
| `config` | `ConfigStoreTest`, `ConnectionsTest`, `PackagesTest` |
| `format` | `BlobsTest`, `MultipartTest`, `SafeXmlTest`, `SoapEnvelopeTest`, `XmlFormatTest` |
| `testsupport` | `FakeBooksApi`, `FakeSoapApi` |

A test that stays next to its class keeps package-private access to it. The `definition` and `link` helpers that `SoapIntegrationTest` and `XmlIntegrationTest` import from `EngineSkeletonTest` stay there, since all three land in `runtime`.

### Widen access only where the compiler asks

Move first, then compile, then add `public` to exactly the class or member each error names. No blanket `public` pass. Where only a test in another package needs a member, change the test before widening production code. Constructors and fields follow the same rule.

Alternative considered: make everything `public` up front. Faster, but it throws away the information the current modifiers carry and hides which cross-package dependencies are real.

### One mechanical commit, then a docs commit

A script runs `git mv` per file and rewrites the `package` line and the imports; the compile-driven access fixes follow in the same commit. Keeping the move apart from other edits lets `git log --follow` and review tools show renames with small diffs. `CLAUDE.md` changes in a separate commit.

## Risks / Trade-offs

- [More `public` API than before] → Only what the compiler requires is widened; the result shows the real cross-package dependencies, which is the point of the change.
- [Logger names change, e.g. `integrationmapper.engine.CallLog` becomes `integrationmapper.engine.runtime.CallLog`] → No logging configuration or documentation refers to them (checked `application.properties`, `Dockerfile`, docs).
- [Open branches touching engine files conflict with the move] → Land it when no other engine work is in flight; git's rename detection resolves most content conflicts.
- [A Spring bean is no longer found] → The root package is unchanged, so component scanning covers every sub-package; `EngineSkeletonTest` and the end-to-end suite would show a missing bean at once.

## Migration Plan

Pure source reorganisation: no data, config or API migration. Rollback is reverting the commits.
