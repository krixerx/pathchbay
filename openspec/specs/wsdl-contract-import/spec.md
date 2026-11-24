# wsdl-contract-import Specification

## Purpose

Lets an analyst import a SOAP service's WSDL contract into Patchbay and use its operations the same way as OpenAPI operations: browse them, choose one for an integration and see its request and response as field trees.

## Requirements

### Requirement: Import a WSDL 1.1 contract
The engine SHALL accept a WSDL 1.1 document as a contract through the same contract import call and console form as OpenAPI, given as a URL, as the text of one file, or as a ZIP that holds the WSDL and the XSD files it refers to.

#### Scenario: Import by URL
- **WHEN** an analyst imports a contract by the URL of a WSDL 1.1 document
- **THEN** the engine stores the contract and lists its SOAP operations

#### Scenario: Import a ZIP with separate schema files
- **WHEN** an analyst uploads a ZIP containing one WSDL and the XSD files it imports, in subfolders
- **THEN** the engine resolves every import inside the ZIP and lists the operations with complete field trees

#### Scenario: Not a contract
- **WHEN** the imported text is neither OpenAPI, Swagger nor WSDL 1.1
- **THEN** the import fails with 400 and a message naming the accepted contract types, and nothing is stored

### Requirement: Imported schemas are resolved safely
The engine SHALL parse WSDL and XSD with DTDs and external entities disabled. It SHALL follow `import` and `include` locations only inside the uploaded ZIP or on the same scheme, host and port as the contract URL, and SHALL refuse locations that leave the ZIP.

#### Scenario: External entity in a WSDL
- **WHEN** an uploaded WSDL declares a DTD with an external entity that points to a local file
- **THEN** the import fails with 400 and the file's content appears nowhere in the stored contract or the answer

#### Scenario: Import that leaves the ZIP
- **WHEN** a schema in an uploaded ZIP imports `../../etc/schema.xsd`
- **THEN** the import fails with 400 naming that location

#### Scenario: Generated schema location on the same host
- **WHEN** a WSDL imported from `http://host:8080/ws/person?wsdl` imports `http://host:8080/ws/person?xsd=1`
- **THEN** the schema is fetched and its types appear in the field trees

#### Scenario: Schema on another host
- **WHEN** a WSDL imported by URL imports a schema from a different host
- **THEN** the schema is not fetched, the contract gets a warning naming it, and the affected fields are shown as text

#### Scenario: Missing schema
- **WHEN** a WSDL imported by URL refers to an XSD that cannot be fetched
- **THEN** the contract is stored with a warning naming the missing schema, and the affected fields are shown as text

### Requirement: SOAP operations are normalized into field trees
For every operation of every port with a SOAP 1.1 or 1.2 document/literal binding, the engine SHALL list an operation whose id is `<port name>/<operation name>` and whose request body and response are field trees built from the XSD elements of the input and output messages.

#### Scenario: WSDL with SOAP 1.1 and SOAP 1.2 ports
- **WHEN** a WSDL has ports `PersonSoap` (SOAP 1.1) and `PersonSoap12` (SOAP 1.2) for the same operations
- **THEN** each operation is listed twice, as `PersonSoap/GetPerson` and `PersonSoap12/GetPerson`, each with its own SOAP version and address

#### Scenario: Ports with different addresses
- **WHEN** two ports of one contract have different service addresses
- **THEN** the contract gets a warning that one connection gives one base URL, and each operation keeps its own address for prefilling

#### Scenario: Types from XSD
- **WHEN** an input element has children typed `xs:int`, `xs:decimal`, `xs:boolean`, `xs:date`, `xs:dateTime`, `xs:string` and `xs:base64Binary`
- **THEN** the fields are typed integer, number, boolean, date, datetime, text and text

#### Scenario: Repeated elements
- **WHEN** an element has `maxOccurs` greater than 1 or `unbounded`
- **THEN** its field is a list of the element's type

#### Scenario: Required fields
- **WHEN** an element has `minOccurs` 0 or is nillable
- **THEN** its field is optional, and otherwise it is required

#### Scenario: Wrapped document/literal
- **WHEN** the input message has one part that refers to an element named like the operation
- **THEN** the request body tree holds that element's children, and calling the operation sends them inside that element

#### Scenario: Unsupported operation styles
- **WHEN** a binding uses RPC style, encoded use, or has only an HTTP binding
- **THEN** its operations are listed with a warning that they are not supported, and they cannot be chosen for an integration

#### Scenario: Recursive types
- **WHEN** an XSD type contains itself
- **THEN** the field is shown as an object without inner fields and the contract gets a warning, as for a recursive OpenAPI schema

### Requirement: SOAP operations can be chosen for an integration
Choosing a SOAP operation SHALL set the integration's target to POST with the operation's SOAP details, prefill Input and Output from its field trees like a REST operation, and prefill the connection's base URL from the WSDL service address when the connection has none.

#### Scenario: Choosing a SOAP operation
- **WHEN** an analyst chooses SOAP operation `GetPerson` for a new integration
- **THEN** the draft's target method is POST, the request format is SOAP, Input and Output definitions are created with links, and the integration's endpoint accepts a JSON body by POST once published

#### Scenario: Service address as base URL
- **WHEN** the WSDL's port address is `http://registry.example/ws/person` and the connection has no base URL
- **THEN** the connection's base URL is set to that address

#### Scenario: SOAP badge in the console
- **WHEN** the console lists the operations of a WSDL contract
- **THEN** each SOAP operation shows a SOAP badge in place of an HTTP method

### Requirement: Re-import keeps the original files
The engine SHALL store the original WSDL and every XSD it used unchanged next to the normalized contract, and a re-import SHALL replace all of them together.

#### Scenario: Re-import with one schema fewer
- **WHEN** a WSDL contract is re-imported from a ZIP that no longer contains one of the earlier XSD files
- **THEN** the stored contract no longer contains that XSD file

### Requirement: WSDL contracts travel in packages
An exported package SHALL contain a WSDL contract's original files and normalized contract, and importing the package SHALL restore them so that its integrations compile and publish without fetching anything.

#### Scenario: Package round trip
- **WHEN** an integration that calls a SOAP operation is exported and the package is imported into an empty Patchbay
- **THEN** the contract with its WSDL and XSD files is present and the integration publishes once its connection is set
