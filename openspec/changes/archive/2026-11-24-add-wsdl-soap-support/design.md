# Design

## Context

See proposal.md for why. The parts of today's engine that shape the approach:

- A contract is `config/contracts/<id>.json` (`Contract`: operations with `method`, `path`, parameters, `requestBody` and `response` as `PayloadDefinition.Field` trees) plus one original file `<id>.openapi.json|yaml`. `ContractApiController` and `Packages.CONTRACT_SOURCE` recognise source files by the `.openapi.` infix, and the import call takes one text `content` or a `url`.
- Choosing an operation (`PUT /admin/api/integrations/{slug}/operation`) copies `method` and `path` into `target` and prefills Input/Output (`Prefill`). The inbound route uses the same HTTP method as `target.method` and reads a JSON body for POST.
- Mapping is DataSonnet over JSON trees (`ScriptGenerator`). Request links target `path.`, `query.`, `headers.` or `body.`.
- D-24 added XML at the edges (`XmlFormat`, Jackson `XmlMapper`): one named root, no namespaces, attributes read but not written, list fix-up from link paths, typing from the Output definition.
- The outbound call is `camel-http`, with headers, auth (bearer/apiKey), connection headers, cookies, timeouts and the call log in `IntegrationProcessors`; automatic retries are switched off (`OutboundHttpConfig`), so each call reaches the external system once. Any remote status of 400 or more passes through byte for byte.
- `Field` has no place for a namespace, element order is the order of the JSON object, and published version files must be self-contained.

## Goals / Non-Goals

**Goals:**
- SOAP calls reuse the existing outbound path (connections, auth, cookies, timeouts, call log, test runner, mocks) rather than running beside it.
- Payload definitions, links and the board stay protocol-neutral: no namespace or SOAP fields in `PayloadDefinition.Field`.
- A published SOAP version needs nothing from the contract files at runtime.

**Non-Goals:**
- A general XML schema mapper: XSD features beyond what document/literal services commonly use (see Decision 4).
- Changing the D-24 XML format for REST integrations.

## Decisions

### 1. Build the envelope over the existing HTTP path, no CXF runtime

The engine writes the SOAP envelope itself and sends it through the existing `camel-http` step as a POST.

Alternative: `camel-cxf-soap` (it is in the Camel Spring Boot BOM 4.23.0 as `camel-cxf-soap-starter`). CXF brings WS-Security, MTOM and full WSDL handling, but in POJO or PAYLOAD mode it expects generated classes or its own message model. It would also make a second outbound path in which the no-retry rule, cookies, connection headers, the call log and the test runner all have to be redone, and it adds a large dependency tree to the image. None of the CXF-only features are in scope. If WS-Security or MTOM are added later, the WS-Security UsernameToken header can be written by the same envelope writer, and CXF can be reconsidered for signatures.

### 2. SOAP metadata lives in a `soap` block of the target, not in fields

`ServiceDefinition.Target` gets an optional `soap` object, filled when a SOAP operation is chosen and copied into every version file:

```json
"target": {
  "connection": "person-registry", "method": "POST", "path": "",
  "soap": {
    "version": "1.1",
    "action": "urn:GetPerson",
    "body": { "name": "GetPerson", "ns": "urn:example:person" },
    "request":  { "ns": { "body.address.street": "urn:example:common" }, "order": { "body": ["firstName", "lastName", "address"] } },
    "address": "http://registry.example/ws/person",
    "response": { "element": "GetPersonResponse", "lists": ["persons[]", "persons[].phones[]"] }
  }
}
```

`response.lists` holds the paths the XSD declares as repeated, so a repeated element that occurs once is still a list even when no response link points into it. `address` is the chosen port's address, used only to prefill the connection. `request.ns` holds only the paths whose namespace differs from the body element's, with `""` for an unqualified element, so `elementFormDefault` and `form` need no flag of their own (the import resolves them per element); `order` holds XSD sequence order per object path. Both are keyed by the same dotted paths that request links target, so they need no field ids and survive Input renames. `formats.request` becomes `soap` for these integrations; `formats.output` keeps json/xml/binary for what callers get.

Alternative: namespace and attribute flags on `PayloadDefinition.Field`. Rejected: Input/Output definitions describe the caller's side, while namespaces belong to the external side, which has no stored definition of its own.

Operation ids are `<port>/<operation>`. A WSDL with a SOAP 1.1 and a SOAP 1.2 port lists every operation once per port, and ids stay stable across re-imports. Alternative: the operation name alone, which collides as soon as a WSDL has two ports and makes `chooseOperation` pick whichever comes first. The contract keeps one connection (D-26). If its ports have different addresses, the import warns, and the connection's base URL is prefilled from the first chosen operation's port.

### 3. Contract storage: a source folder per contract

A WSDL contract stores `config/contracts/<id>.json` (normalized, with `protocol: "soap"` on each operation and a `soap` block like Decision 2's) and its originals in `config/contracts/<id>.wsdl/` keeping the relative paths the WSDL and XSD files use. `Contract` gets `sourceKind` (`openapi`/`wsdl`); `sourceFile` names the main WSDL inside that folder. `Packages` and the contract delete/list code match the folder as well as `.openapi.` files. The import call accepts `{"zip": "<base64>"}` beside `content` and `url`; the console sends a `.zip` upload that way.

### 4. Parsing: JDK DOM for WSDL 1.1, Apache XmlSchema for XSD

WSDL 1.1 is a small vocabulary (types, message, portType, binding, service) and is read with the JDK's DOM parser. XSD is read with Apache XmlSchema (`org.apache.ws.xmlschema:xmlschema-core`, latest release 2.3.3 on Maven Central at the time of writing; an Apache project, licence to confirm in its POM when it is added), which resolves imports, includes, named and anonymous types, extension and element references.

Supported XSD subset: element, complexType with sequence/all, simpleContent, complexContent extension, simple types with restrictions (mapped to their base type), `minOccurs`/`maxOccurs`, `nillable`, element `ref`. A `choice` takes its first option, and `any`, attributes, substitution groups and `xs:anyType` become text or are skipped, each with a warning, like oneOf/anyOf in the OpenAPI importer.

Alternative: wsdl4j. Its POM declares CPL-1.0, which is weak copyleft and needs a licence check against NFR-009. It would also only replace the easy part.

Both parsers get a hardened `DocumentBuilderFactory` (`disallow-doctype-decl`, no external entities, no XInclude) and a custom resolver. The resolver serves imports from the ZIP, or fetches them through the existing `ContractImporter.fetch` with the same timeout when they resolve to the contract URL's scheme, host and port. That covers relative paths and the `?xsd=N` locations many SOAP stacks generate. It refuses paths that leave the ZIP and `file:` and other schemes. Locations on other hosts are skipped with a warning.

### 5. Writing and reading the envelope

Writing uses StAX (`XMLStreamWriter`, in the JDK): the envelope, then the body element, then the mapped JSON tree with `soap.request.order` and `soap.request.ns`. JSON null becomes `xsi:nil="true"`, and a list becomes repeated elements. Values are written as their JSON text; dates already arrive as ISO text.

Reading parses namespace-aware with the same hardened factory. It finds `Body` (SOAP 1.1 or 1.2 namespace), and if the first child is `Fault` it hands over to Decision 6. Otherwise it takes the first child, drops prefixes and converts it to a JSON tree. It then reuses `XmlFormat`'s list fix-up, with `soap.response.lists` added to the link-derived paths, and `typed()`, so that lists and types behave as in D-24.

### 6. Faults become `remote-fault` problems

A Fault is recognised on any status, since some services answer 200 with a Fault. The caller gets the engine's error shape `{"error":"remote-fault","integration":...,"detail":<reason>,"fault":{"code","reason","detail"}}` with `X-Integration-Error: remote`. The status is the remote one if it was 400 or more, otherwise 502. A non-SOAP error body still passes through unchanged. This is the one place where a SOAP integration differs from the "remote errors pass through byte for byte" rule: a JSON caller can't use a SOAP fault envelope. The call log keeps the original envelope.

### 7. Basic auth is generic

`Auth` accepts `{"type":"basic"}`. The secret (same lookup as today) must contain a colon, which is checked at publish. This is a small, separate addition, but most SOAP services in the target domain need it, so it ships with this change.

## Risks / Trade-offs

- [Real-world WSDLs use XSD features outside the subset] → Unsupported constructs become warnings and text fields rather than failed imports. Test against a few public WSDLs, including a large ministry-style one, and a fixture with nested imports.
- [Namespace rules (`elementFormDefault`, `form` per element, imported schemas with other target namespaces) are easy to get subtly wrong] → The per-path `ns` map is computed once at import and is easy to inspect in the version file. End-to-end tests assert XPath with namespaces on what WireMock received.
- [Element order taken from the JSON tree would break strict services] → `order` from the XSD sequence is applied when writing; tests send links in reverse order.
- [Fetching imports by URL is a server-side request the engine makes on behalf of the analyst] → Only locations on the contract URL's scheme, host and port are fetched, through the existing fetch with its timeout. A location elsewhere becomes a warning. Admin access is already required to import.
- [Fault-to-JSON departs from byte-for-byte pass-through] → It is documented in D-30 and only applies when the body really is a SOAP Fault.

## Migration Plan

Additive. Existing contracts, service files, version files and packages have no `soap` blocks and behave exactly as before. A package with a WSDL contract cannot be imported into an older Patchbay; this is accepted, as for earlier additions. Rollback means removing the SOAP integrations before downgrading.

## Open Questions

- Whether `xs:base64Binary` should become a `blob` field (D-29) rather than base64 text. Text is the starting point and matches how OpenAPI `format: byte` is handled; it can change later without changing the envelope.
