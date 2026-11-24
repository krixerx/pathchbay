# Tasks

## 1. Decision record and dependency

- [x] 1.1 Add D-30 "SOAP over the existing HTTP path" to `docs/slice-plan.md` covering scope, out-of-scope list, the `soap` target block, operation ids and the remote-fault rule, and verify it references this change
- [x] 1.2 Add `org.apache.ws.xmlschema:xmlschema-core` to `engine/pom.xml` after confirming its licence in the published POM is Apache-2.0, and verify `mvn -q dependency:tree` shows no new non-Apache-compatible licences

## 2. Safe XML parsing

- [x] 2.1 Add a hardened DOM/StAX factory (no DTD, no external entities, no XInclude) and a resolver that serves ZIP entries or fetches same scheme/host/port URLs only, and verify with unit tests for an XXE file read, a `../` ZIP escape, a `file:` location, a `?xsd=1` location on the contract host and a location on another host

## 3. WSDL contract import

- [x] 3.1 Parse WSDL 1.1 (messages, portTypes, SOAP 1.1/1.2 bindings, ports and addresses) into SOAP operations with ids `<port>/<operation>`, action, version, body element and address, warning when ports have different addresses, and verify with a unit test on a fixture with a SOAP 1.1 and a SOAP 1.2 port
- [x] 3.2 Build request and response field trees from XSD via XmlSchema for the supported subset, with warnings for the rest, and verify unit tests for types, lists, `minOccurs`/nillable, wrapped document/literal, recursion, `choice` and `any`
- [x] 3.3 Compute per-operation SOAP metadata (`request.ns` with "" for unqualified elements, `request.order`, `response.lists`) and verify a unit test with an imported schema in a second namespace, an unqualified schema and a nested repeated element
- [x] 3.4 Mark RPC, encoded and HTTP-binding operations as listed-but-unsupported and verify choosing one answers 400
- [x] 3.5 Store originals under `config/contracts/<id>.wsdl/` with `sourceKind`, accept `zip` in `POST`/`PUT /admin/api/contracts`, replace all files on re-import and delete the folder on contract delete, and verify with controller tests for URL, text and ZIP imports and a re-import with one XSD fewer
- [x] 3.6 Recognise WSDL in the import call and fail non-contracts with a message naming OpenAPI, Swagger and WSDL 1.1, and verify the existing OpenAPI import tests still pass
- [x] 3.7 Document WSDL import (accepted forms, schema location rule, supported XSD subset, operation ids) in `engine/README.md` and the contract import text of `README.md`, and verify the curl import example runs against a local engine

## 4. Choosing a SOAP operation

- [x] 4.1 Add the `soap` block to `ServiceDefinition.Target` and `soap` to `Formats.REQUEST` with checks, copy it into version files, and verify a publish/restart test reads it back from the version file
- [x] 4.2 Make `chooseOperation` set POST, the `soap` block and `formats.request = soap` for SOAP operations, prefill Input/Output with `Prefill`, and prefill the connection base URL from the operation's port address, and verify with a controller test
- [x] 4.3 Show SOAP operations with a SOAP badge in the console's operation list, accept `.wsdl` and `.zip` in contract import, and lock the request format switch to SOAP, and verify in the running console against a fixture WSDL
- [x] 4.4 Document the `soap` target block and `formats.request: soap` in `engine/README.md` next to the D-24 formats paragraph, and verify the example matches a draft written by `chooseOperation`

## 5. Outbound SOAP call

- [x] 5.1 Write the envelope with StAX (SOAP 1.1/1.2 namespaces, body element, `ns` map, sequence order, `xsi:nil`, repeated list elements), and verify unit tests against expected XML for both versions, qualified and unqualified schemas, and reversed link order
- [x] 5.2 Send it through the existing HTTP step with the version's Content-Type and SOAPAction/action parameter, and verify a route test against a local fake asserts method, headers and body
- [x] 5.3 Read the response envelope (find Body, unwrap the first child, drop prefixes, list fix-up with `response.lists` plus link paths, typing via `XmlFormat`), and verify route tests for prefixed responses and a repeated element that occurs once without a link into it
- [x] 5.4 Turn SOAP 1.1 and 1.2 Faults into `remote-fault` problems with the status rule, while non-SOAP error bodies pass through unchanged, and verify route tests for a fault on 500 (one request sent), a fault on 200 and an HTML 503
- [x] 5.5 Show the sent and received envelopes as text in the call log and test runner, and verify with the existing call-log test extended to a SOAP integration
- [x] 5.6 Generate SOAP mock answers in `MockGenerator` from the response tree, wrapped in an envelope, and verify a dry-run test call of a SOAP integration succeeds
- [x] 5.7 Document the envelope rules and the `remote-fault` error in `engine/README.md`, remove SOAP from its "not yet" list, and verify the documented fault example matches the route test's output

## 6. Basic auth

- [x] 6.1 Accept integration auth type `basic`, require `username:password` at publish, send `Authorization: Basic`, add it to the console's auth choice, and document it in the README's outbound auth section, and verify unit tests for the header, the publish error and that no secret reaches service, version or package files

## 7. Packages

- [x] 7.1 Export and import a WSDL contract's folder and normalized file in packages (`Packages.CONTRACT_SOURCE` and checks), note it in the README's packages section, and verify an export and import round trip into an empty config where the SOAP integration publishes without network access

## Workflow follow-up

- In the sibling project PathchbayTestAndMock (outside this change's edit root): add a SOAP 1.1 and a SOAP 1.2 WSDL with one XSD import under `mock/__files/wsdl/`, WireMock stubs that match SOAPAction and body by namespaced XPath and answer echo envelopes plus a fault, integrations in `patchbay/package`, and SOAP-aware checks in `SetupTest`; then run `mvn test` with the new SOAP test class alongside the existing 178 tests.
- Archive the change after review with `openspec archive add-wsdl-soap-support`, which creates `openspec/specs/wsdl-contract-import` and `openspec/specs/soap-outbound-call`.
