# Tasks

## 1. Whole-object links

- [x] 1.1 Add shared cases to `rules/link-cases.json` for object-to-object links with the target type unknown and with the source type unknown, plus a `ScriptGeneratorTest` for each direction; verify they fail with an NPE on the current code (3 NPEs on the old code)
- [x] 1.2 Make `LinkRules.checkShapes` and the `contains` checks in `PayloadDefinition`, `SoapTarget` and `ServiceDefinition.Auth` null-safe. Verify that 1.1 passes, that a definition field without `type` gives "unknown type" (`PayloadDefinitionTest.aMissingTypeIsAProblemNotAnError`), and that the console's Vitest run passes the new cases (91 tests, typecheck clean)

## 2. Drafts and files

- [x] 2.1 Add a test where a draft save and a publish of the same slug run at the same time, and assert that `activeVersion` ends at the published version (`VersionLifecycleTest.aDraftEditDuringPublishKeepsBothChanges`); verified failing with the lock disabled
- [x] 2.2 Add `config/Drafts` (read, write under a per-slug lock, `edit`). Move the controllers, `IntegrationPublisher`, `Packages` and `ConfigLoader`'s startup base-URL move onto it. Verify that 2.1 passes. Because every console save now re-reads the draft under the lock, it keeps the stored `activeVersion` without special handling
- [x] 2.3 Make `ConfigStore.write` use a unique temp name opened with `CREATE_NEW` in the target folder (not `Files.createTempFile`, which would make every config file 0600 on POSIX), keeping the Windows rename retries. Verify with a concurrent-write test (`ConfigStoreTest.twoWritesOfOneFileAtOnceLeaveOneWholeFile`, which fails on the old code)

## 3. Publish

- [x] 3.1 Add a test where Camel fails to add the route during a swap (a literal base URL with an unknown scheme), and assert that the old version still answers and the service file and version list are unchanged (`VersionLifecycleTest.failedRouteSwapKeepsTheLiveVersionAndChangesNoFiles`, which fails on the old publisher)
- [x] 3.2 Keep the R9 order (version file, `activeVersion`, route). On a failed swap, remove what the failed add left behind, add the previous version's route back, restore `activeVersion` and delete the new version file. If the previous route can't be restored either, mark the integration broken. Same for `activateVersion`. Verify that 3.1 passes

## 4. Connections, packages, startup

- [x] 4.1 Changed during the build (see design notes in proposal.md): the reported failure (a base URL with a query turns external errors into 502) did not reproduce, because the external 503 still passed through. `Connections.check` now refuses an unparsable URL (including `{{`), a missing host, user info and a fragment. A query stays allowed, because SOAP endpoints use one. The route endpoint is unchanged. The host is checked through the URI's authority, so Docker service names with an underscore (`my_service`) stay valid. Verified with `ConnectionsTest.aBaseUrlMustBeAPlainHttpUrl`. Found while doing this and not fixed: in a REST base URL with a query, the operation path is appended after the query (`http://h/api?key=1` + `/books`)
- [x] 4.2 Validate manifest ids against `SLUG` in phase 1 of `Packages` and require a draft for each. Verify that a manifest with an unknown id is refused with no file written (`PackagesTest.aManifestIntegrationWithoutADraftRefusesTheWholeImport`, which failed on the old code with `FileNotFoundException` after writing)
- [x] 4.3 Make `IntegrationRegistry.markBroken` and `ConfigLoader` handle an exception without a message, and verify with `IntegrationRegistryTest` (NPE on the old code)

## 5. End-to-end

- [x] 5.1 Add the PathchbayTestAndMock tests: `shop-order-whole` (whole-object links both ways, `NestedMappingTest.anObjectLinkedAsAWholeIsCopiedInBothDirections`) and `ErrorHandlingTest.aConnectionBaseUrlThatIsNotAPlainUrlIsRefusedAndNothingChanges`. Rebuild the container and verify all tests pass (204, up from 198)

Engine: `mvn test` 218 tests pass.
