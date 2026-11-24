# Design

## Context

See proposal.md for why. Today `model/Contract.java` holds one record `Contract` (with optional `sourceKind` and `files`), a nested record `Operation` (with optional `protocol`, `soap` and `unsupported`) and `Parameter`. Both importers build these records, and five callers read them from `config/contracts/<id>.json` with `Json.MAPPER.readValue(..., Contract.class)`: `ContractApiController`, `MappingApiController`, `TestRunner` and `Packages` (twice). `ContractApiController` also rebuilds a contract to rename it.

The JSON is read by the console (`engine/console/src/model.ts`), where `ContractOperation.method`, `path` and `parameters` are required. A SOAP operation therefore stores `"method":"POST"`, `"path":""`, `"parameters":[]` and `"responseStatus":"200"` today, and must keep doing so. Exported packages carry the same files to other environments, which may run an older engine.

Jackson 2 (`Json.MAPPER`, databind 2.21) reads and writes these records.

## Goals / Non-Goals

**Goals:**

- One type per contract kind and per operation kind, each holding only its own fields.
- Adding a kind later means adding a record and a type name; every `switch` over the sealed types that misses it fails to compile.
- Stored JSON byte-for-byte the same in both directions, proven by tests on files written by the current code.

**Non-Goals:**

- A new contract kind, or changes to the JSON layout or the console model.
- `ServiceDefinition`, whose `target.soap` block is the per-integration copy and stays as it is.

## Decisions

### Types

In package `model`, one file each:

```java
public sealed interface Contract permits OpenApiContract, WsdlContract {
    String id(); String name(); String sourceFile(); String sourceUrl(); String importedAt();
    List<String> servers(); List<? extends Operation> operations(); List<String> warnings();
    Contract withName(String name);   // the rename in ContractApiController
}
public record OpenApiContract(id, name, sourceFile, sourceUrl, importedAt, servers, List<RestOperation> operations, warnings)
public record WsdlContract(id, name, sourceFile, sourceUrl, importedAt, servers, List<SoapOperation> operations, warnings,
                           Map<String, String> files)

public sealed interface Operation permits RestOperation, SoapOperation {
    String id(); String summary(); PayloadDefinition.Field requestBody(); PayloadDefinition.Field response();
}
public record RestOperation(id, method, path, summary, List<Parameter> parameters, requestBody, responseStatus, response) {
    public record Parameter(String name, String in, boolean required, String type) {}
}
public record SoapOperation(id, summary, requestBody, response, SoapTarget soap, String unsupported)
```

A contract's kind fixes its operations' kind: an OpenAPI contract has only REST operations, a WSDL contract only SOAP ones. Typing the lists that way means Jackson never has to choose an operation type on its own, so `Operation` needs no type annotations.

Alternative considered: one `Operation` list of the sealed type on every contract, with `protocol` choosing the record. It allows mixed contracts, which no supported format produces, and it adds a second polymorphic mapping to keep compatible.

### Reading and writing the same JSON

- `Contract` carries `@JsonTypeInfo(use = NAME, include = EXISTING_PROPERTY, property = "sourceKind", visible = true, defaultImpl = OpenApiContract.class)` and `@JsonSubTypes` naming `wsdl` for `WsdlContract`. A file without `sourceKind` (every OpenAPI contract today) reads as `OpenApiContract`, and `OpenApiContract` writes no `sourceKind`, as now.
- `WsdlContract` writes `"sourceKind":"wsdl"` through a constant `@JsonProperty("sourceKind")` accessor and ignores it on reading (`@JsonIgnoreProperties(value = "sourceKind", allowGetters = true)`).
- `SoapOperation` writes the fields the console expects through constant accessors: `protocol` `"soap"`, `method` `"POST"`, `path` `""`, `parameters` `[]`, `responseStatus` `"200"`. It ignores them on reading in the same way. Field order in the written JSON follows the current file, using `@JsonPropertyOrder` where the record order differs.
- `@JsonInclude(NON_NULL)` stays on every record, as today.

Alternative considered: `include = PROPERTY` and letting Jackson write the type id. That would add `"sourceKind":"openapi"` to OpenAPI files and change their bytes.

### Callers switch on the type

`isWsdl()` and `isSoap()` are removed. Callers use `instanceof` patterns or a `switch` over the sealed interface, for example `Packages` (`c instanceof WsdlContract w` → `w.files()`), `ContractApiController.chooseOperation` (REST: method, path and parameters into the target; SOAP: `soap` block, POST and the address), `Prefill` (parameters only for `RestOperation`) and `TestRunner` (`responseStatus` from a `RestOperation`, `"200"` for SOAP as stored today). The constants move with the types: `Contract.WSDL` becomes `WsdlContract.KIND` and `Contract.Operation.SOAP` becomes `SoapOperation.PROTOCOL`.

### Compatibility is proven with fixtures written by today's code

Before changing anything, a test resource gets one stored OpenAPI contract (with a server, parameters and a `$ref` warning) and one stored WSDL contract (SOAP 1.1 and 1.2 operations, one `unsupported`, with `files`), both written by the current importers. A new test reads each file into `Contract` and writes it back, and the result must equal the original bytes. It runs before and after the change.

## Risks / Trade-offs

- [Jackson's handling of records with `EXISTING_PROPERTY` and ignored constant properties differs from the plan] → The byte-for-byte round-trip test fails before anything ships; fall back to an explicit `@JsonCreator` on the affected record.
- [An older engine imports a package exported by the new one] → The files are identical, so nothing changes for it.
- [More files and types in `model`] → Each is small; the kinds now have names a reader can find.
- [Callers that only need `id` or the bodies now see an interface] → Common fields stay on the interfaces, so those callers do not change.

## Migration Plan

None: the stored format is unchanged. Rollback is reverting the commit.
