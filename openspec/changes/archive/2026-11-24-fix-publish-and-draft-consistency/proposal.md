# Proposal

## Why

The 2026-11-24 engine review found bugs where publish, drafts and the files on disk can disagree. Each item below was reproduced with a test before it was fixed, except the base URL item, whose reported failure did not reproduce (see that item).

- **Whole-object links can't be published.** `LinkRules.checkShapes` calls `SINGLE_TYPES.contains(type)` on a `List.of`, which throws an NPE for a null type. `ScriptGenerator` passes null for the target type of every request link and for the source type of ordinary response links. An object-to-object link is an allowed rule (`rules/link-cases.json`, "object into an object"), yet publishing one fails with an NPE instead of the R8 problem list. The shared cases pass both types, so they don't catch this. `PayloadDefinition` (missing `type`), `SoapTarget` (missing `version`) and `ServiceDefinition.Auth` (missing `in`) have the same `contains(null)` pattern.
- **A console save can undo a publish.** The draft endpoints in `MappingApiController`, `ContractApiController` and `ConsoleApiController` read, modify and write `services/<slug>.json` with no lock. `IntegrationPublisher.setActiveVersion` writes the same file under the publisher's own monitor. If an autosave reads before a publish and writes after it, `activeVersion` goes back to the old version. The route keeps serving the new one until a restart, and after the restart the old one is live again. R3 ("last save wins") covers two editors, not one editor racing the publisher.
- **Two writes can tear a file.** `ConfigStore.write` always uses the same `<file>.tmp` name, so two concurrent writers of one file share the temp file, and the rename then installs a mixed file. This defeats R4.
- **A failed route swap loses the live version.** In `IntegrationPublisher.swap`, the old route is removed and the registry updated before `addRouteFromTemplate`. If adding the route throws, the integration answers 404, while the service file says the new version is active.
- **A base URL is checked only for its scheme.** `IntegrationRoutes` puts the base URL into the Camel endpoint (`{{baseUrl}}?throwExceptionOnFailure=false`), so a `{{` in it would be read as a Camel placeholder, and user info or a fragment would not be sent as meant. The review also claimed that a query in the base URL turns external errors into 502; that did not reproduce (an external 503 still passed through), so it is not part of this change.
- **A package import can fail after it has started writing.** A manifest integration id with no draft makes `Packages.needs` throw `FileNotFoundException` once phase 2 has already written files. That contradicts "throws before writing anything". At startup, `ConfigLoader.markBroken` puts a null message into a `ConcurrentHashMap` and aborts the loader (R10).

## What Changes

- Null-safe type checks in `LinkRules`, `PayloadDefinition`, `SoapTarget` and `ServiceDefinition.Auth`. A missing type gives the existing problem text, not an NPE. New shared link cases cover whole-object links with an unknown type on one side.
- One `Drafts` service owns reading and writing a draft under a per-slug lock. The controllers, `IntegrationPublisher`, `Packages` and `ConfigLoader` all use it. Every console save re-reads the draft under the lock, so it keeps the stored `activeVersion`.
- `ConfigStore.write` uses a unique temp file in the target folder.
- Publish keeps the R9 order (version file, `activeVersion`, route). When the new route can't be added, the previous route is added back, `activeVersion` is restored and the new version file is deleted, so on failure nothing on disk changes (R9). If the previous route can't be restored either, the integration is marked broken (R10). `activateVersion` does the same.
- `Connections.check` refuses a base URL that is not a valid URL (this includes `{{`), has no host, or has user info or a fragment. A query stays allowed, because SOAP endpoints use one (SAP's `?sap-client=100`). The route endpoint is unchanged.
- `Packages` validates manifest ids in phase 1 and requires a draft for each one. `ConfigLoader` copes with an exception that has no message.

The stored JSON, the package format and the error JSON shape do not change.

**Ordering:** the NPE fix makes whole-object links publishable, and with them the XML name injection described in `harden-inbound-boundary`. Land that change first, or both together.

Out of scope: several analysts editing at once (R3 stays), version history UI, and a database.

## Capabilities

### New Capabilities

- `integration-publishing`: what publish guarantees about the live version, the files on disk and concurrent draft saves.

### Modified Capabilities

None.

## Impact

- Engine: `mapping/LinkRules`, `model/PayloadDefinition`, `model/SoapTarget`, `model/ServiceDefinition`, a new `config/Drafts`, `config/ConfigStore`, `config/Packages`, `config/Connections`, `runtime/IntegrationPublisher`, `runtime/IntegrationRegistry`, `runtime/ConfigLoader`, and the controllers in `api` that write drafts.
- `rules/link-cases.json`: new whole-object cases. The console must run them too.
- PathchbayTestAndMock: an end-to-end integration that links an object as a whole in both directions, and connection base URLs with user info, a fragment or `{{` that are refused. The existing 198 tests must still pass.
- No new dependencies.
