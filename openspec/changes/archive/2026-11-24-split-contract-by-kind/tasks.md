# Tasks

## 1. Compatibility fixtures first

- [x] 1.1 Create a branch from `console/foundation`, write one OpenAPI and one WSDL contract with the current importers into `engine/src/test/resources/stored-contracts/` (as design.md describes), and add `StoredContractJsonTest`, which reads each into `Contract` and writes it back; verify it passes on the unchanged code and that `mvn test` gives the baseline count plus the new tests

## 2. New types

- [x] 2.1 Add `Contract` (sealed interface), `OpenApiContract`, `WsdlContract`, `Operation` (sealed interface), `RestOperation` with nested `Parameter`, and `SoapOperation` in `model`, with the Jackson annotations from design.md, replacing the old records; verify `mvn -q compile` reports only errors in callers, not in the new types
- [x] 2.2 Make both importers build the new records (`OpenApiImporter` returns `RestOperation`s, `WsdlImporter` `SoapOperation`s, `ContractImporter` the two contract records), and verify `mvn -q compile` reports no errors in `contract`
- [x] 2.3 Change `ContractApiController`, `MappingApiController`, `TestRunner`, `Prefill` and `Packages` to switch on the types instead of `isWsdl()`/`isSoap()`, and verify `mvn -q compile` succeeds and no `isWsdl`/`isSoap` remains

## 3. Tests and docs

- [x] 3.1 Update the tests that build or read contracts and operations (`PrefillTest`, `ContractImporterTest` and others the compiler names), and verify `mvn test` passes with the count from 1.1, including `StoredContractJsonTest` unchanged
- [x] 3.2 Extend the stored-record note in `CLAUDE.md` with how contract and operation kinds map to JSON (`sourceKind`, constant SOAP fields), and verify it names the new types correctly
- [x] 3.3 Rebuild the Patchbay container from PathchbayTestAndMock and run its `mvn test`, and verify all 196 tests pass
- [x] 3.4 Commit on the branch, and verify `git show --stat` lists only engine sources, tests, test resources, `CLAUDE.md` and this change

## Workflow follow-up

- Merge into `console/foundation` and archive the change after review.
