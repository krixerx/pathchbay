# integration-publishing Specification

## Purpose

Defines what publishing guarantees about the live version of an integration, the files on disk and draft saves that happen at the same time.

## Requirements

### Requirement: Whole-object links can be published
The engine SHALL publish an integration that links an object to an object as a whole in either direction, including when the type of one side is unknown. A field without a type SHALL produce a problem in the publish result, not an internal error.

#### Scenario: Input object linked to a body object with no schema type
- **WHEN** an integration links the Input object `customer` to `body.customer` and the contract gives no type for `body.customer`
- **THEN** publish succeeds and the external system receives the caller's `customer` object

#### Scenario: Definition field without a type
- **WHEN** a payload definition has a field without `type` and its integration is published
- **THEN** publish answers with a problem that names the field, and no version is written

### Requirement: A draft save never changes the active version
Saving a draft from the console SHALL keep the stored `activeVersion`, and reading and writing a draft SHALL be serialised per integration with publishing.

#### Scenario: Autosave races a publish
- **WHEN** a console save of integration `x` reads the draft before a publish of `x` to 1.1.0 and writes after it
- **THEN** the draft still names 1.1.0 as active, and after a restart 1.1.0 is served

### Requirement: Config files are never torn
Two concurrent writes of the same config file SHALL leave the file holding one complete version.

#### Scenario: Two writers
- **WHEN** two requests write `services/x.json` at the same time
- **THEN** the file afterwards is valid JSON equal to one of the two writes

### Requirement: A failed swap keeps the live version
If the new route cannot be added during publish, the engine SHALL keep serving the previous version and SHALL leave `activeVersion` naming it.

#### Scenario: Route add fails
- **WHEN** publishing version 1.1.0 of `x` fails while adding its route
- **THEN** calls to `x` are still answered by 1.0.0, the draft names 1.0.0 as active, no version file 1.1.0 exists, and publish reports the failure

### Requirement: Base URLs are plain http(s) URLs
The engine SHALL refuse a connection base URL that is not a valid absolute http(s) URL with a host, or that contains user info or a fragment. A query SHALL stay allowed.

#### Scenario: Base URL with user info
- **WHEN** a connection is saved with base URL `https://user:pw@api.example/v1`
- **THEN** the save is refused with a problem that names the user name or password, and the stored connection is unchanged

#### Scenario: Base URL with a placeholder
- **WHEN** a connection is saved with base URL `https://api.example/{{secret}}`
- **THEN** the save is refused as not a valid URL

#### Scenario: SOAP endpoint with a query
- **WHEN** a connection is saved with base URL `http://sap.example:8000/sap/bc/srt/rfc/sap/zperson?sap-client=100`
- **THEN** the save succeeds

### Requirement: Package import validates before writing
The engine SHALL validate every integration id in a package manifest, and SHALL require a draft for each one, before writing any file.

#### Scenario: Manifest names an integration with no draft
- **WHEN** a package manifest lists integration `ghost` and neither the package nor the config folder has `services/ghost.json`
- **THEN** the import is refused with a problem naming `ghost`, and no file is written
