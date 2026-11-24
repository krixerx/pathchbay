# soap-outbound-call Specification

## Purpose

Lets a published integration call a SOAP operation: Patchbay builds the SOAP request from the mapped Input, sends it, and maps the SOAP response or fault back to the caller's JSON Output.

## Requirements

### Requirement: Request is sent as a SOAP envelope
For an integration whose target is a SOAP operation, the engine SHALL send the mapped request body as the content of a SOAP envelope's Body, inside the operation's body element, with every element in the namespace the XSD gives it.

#### Scenario: Namespaced elements
- **WHEN** the operation's body element `GetPerson` and its children are in namespace `urn:example:person` and the caller sends `{"personCode":"38001010000"}`
- **THEN** the external system receives a SOAP envelope whose Body holds `GetPerson` with child `personCode` = `38001010000`, both in namespace `urn:example:person`

#### Scenario: Unqualified local elements
- **WHEN** the schema has `elementFormDefault="unqualified"`
- **THEN** only the body element is namespace-qualified and its children are not

#### Scenario: Element order
- **WHEN** an XSD sequence declares `firstName` before `lastName` and the mapping produces them in another order
- **THEN** the elements are sent in the sequence's order

#### Scenario: Field not sent
- **WHEN** the caller leaves out an optional Input field
- **THEN** its element is left out of the envelope, and an explicit null is sent as an element with `xsi:nil="true"`

### Requirement: SOAP version decides transport headers
The engine SHALL POST the envelope to the connection's base URL. For SOAP 1.1 it SHALL use Content-Type `text/xml; charset=utf-8` and a `SOAPAction` header with the operation's action. For SOAP 1.2 it SHALL use `application/soap+xml; charset=utf-8` with the action as the `action` parameter.

#### Scenario: SOAP 1.1 call
- **WHEN** an integration calls a SOAP 1.1 operation whose soapAction is `urn:GetPerson`
- **THEN** the request has method POST, Content-Type `text/xml; charset=utf-8` and header `SOAPAction: "urn:GetPerson"`

#### Scenario: SOAP 1.2 call
- **WHEN** an integration calls a SOAP 1.2 operation whose soapAction is `urn:GetPerson`
- **THEN** the request has Content-Type `application/soap+xml; charset=utf-8; action="urn:GetPerson"` and no SOAPAction header

### Requirement: Response body is mapped to the Output
On a successful answer, the engine SHALL take the first element inside the SOAP Body, read its children by local name regardless of namespace prefix, give values the Output definition's types, and map them to the Output through the response links.

#### Scenario: Prefixes do not matter
- **WHEN** the response uses prefix `ns2` for its elements and the caller's Output links `name` to the response field `name`
- **THEN** the caller gets `{"name": ...}` with the element's value

#### Scenario: List of one
- **WHEN** a response element that the XSD declares as repeated occurs once
- **THEN** it is mapped as a list with one item

### Requirement: SOAP faults reach the caller as JSON
When the answer's Body holds a SOAP 1.1 or 1.2 Fault, the engine SHALL answer the caller with a JSON problem holding the fault code, reason and detail, header `X-Integration-Error: remote`, and the remote HTTP status, or 502 when the remote status was below 400.

#### Scenario: Fault with HTTP 500
- **WHEN** the external system answers 500 with a SOAP 1.1 Fault whose faultcode is `soap:Client` and faultstring is `Unknown person`
- **THEN** the caller gets 500, `X-Integration-Error: remote` and the engine's usual JSON error with `error` = `remote-fault`, `detail` = `Unknown person`, and a `fault` object with `code` = `soap:Client`, `reason` = `Unknown person` and the fault detail as JSON

#### Scenario: Fault is not retried
- **WHEN** the external system answers with a SOAP Fault
- **THEN** the engine has sent exactly one request to it

#### Scenario: Fault with HTTP 200
- **WHEN** the external system answers 200 with a SOAP Fault in the Body
- **THEN** the caller gets 502 with the same JSON problem, and no Output mapping runs

#### Scenario: Non-SOAP error
- **WHEN** the external system answers 503 with an HTML page
- **THEN** the status and body pass through unchanged, as for a REST integration

### Requirement: Outbound HTTP Basic authentication
An integration SHALL support auth type `basic`, which sends `Authorization: Basic` built from a secret of the form `username:password`, read from the same places as other secrets and never stored in service, version or package files.

#### Scenario: Basic auth on a SOAP call
- **WHEN** an integration with auth `basic` and secret `svc:pa55` is called
- **THEN** the external request carries `Authorization: Basic c3ZjOnBhNTU=`

#### Scenario: Secret without a colon
- **WHEN** an integration with auth `basic` is published and its secret has no colon
- **THEN** publishing fails with 400 saying the secret must be `username:password`

### Requirement: SOAP integrations are callable like any other
A published SOAP integration SHALL be served at `/api/v1/integrations/<slug>` by POST with a JSON body, validate input against its Input definition, appear in `/api/openapi.json`, and follow the existing timeout, cookie, call log and test-run behaviour, calling the external system exactly once.

#### Scenario: Invalid input
- **WHEN** a caller sends a body that does not match the Input definition
- **THEN** the caller gets 400 listing every problem and the SOAP service is not called

#### Scenario: Call log shows the envelope
- **WHEN** call logging is on and a SOAP integration is called
- **THEN** the call log shows the sent envelope and the received envelope as text

#### Scenario: Version files are self-contained
- **WHEN** a SOAP integration is published and its WSDL contract is later re-imported with different namespaces
- **THEN** the published version keeps sending the namespaces it was published with until it is published again
