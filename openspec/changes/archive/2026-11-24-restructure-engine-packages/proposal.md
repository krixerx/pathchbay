# Proposal

## Why

All 44 engine classes and their 33 tests sit in one package, `integrationmapper.engine`. Controllers, the stored JSON records, the Camel runtime, the contract importers and the wire formats are mixed together. That makes it hard to see where a piece of code belongs or what depends on what. The engine is still small enough to restructure in one change, and every later feature makes it more expensive.

## What Changes

- Split `integrationmapper.engine` into sub-packages by role: `api` (REST controllers), `model` (records stored as JSON), `web` (Spring HTTP configuration and filters), `runtime` (publishing and serving integrations), `mapping` (links, scripts and payload definitions built from contracts), `contract` (contract import), `config` (the config folder, connections, secrets, packages) and `format` (JSON, XML, SOAP, multipart, blobs). `EngineApplication` stays in the root package.
- Move each test into the package of the class it tests; the shared test fakes go to a `testsupport` package.
- Make classes and members `public` only where a caller in another package needs them.
- Update `CLAUDE.md`, which names the engine package, to describe the new layout.

No behaviour changes: endpoints, request and response bodies, config files, version files, packages, properties and environment variables stay as they are. Nothing is **BREAKING** for callers, the console or existing config folders.

Out of scope: renaming classes, splitting large classes, changing the `integrationmapper` root name, and the console code.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None. This is a code-organisation change with no spec-level behaviour; the change sets `skip_specs: true`.

## Impact

- Engine: every file under `engine/src/main/java/integrationmapper/engine` and `engine/src/test/java/integrationmapper/engine` moves or gets new `package`/`import` lines. About 12 package-private classes and roughly 100 package-private methods become candidates for `public`.
- Docs: `CLAUDE.md`. Archived openspec changes keep their old paths as history.
- End-to-end tests in the sibling project PathchbayTestAndMock run against the rebuilt container unchanged and must still pass.
- No dependency, configuration or API changes.
