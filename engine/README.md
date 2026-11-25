# Patchbay engine

Spring Boot 4.1.1, Apache Camel 4.23.0, Java 21, with the console (React, in `console/`) built into the same jar. Plan: `../docs/slice-plan.md`.

How to install, run and configure it (Docker or not, all variables): see `../README.md`. This file is the reference for the files and calls behind the console.

## Examples and admin calls

Startup loads the payload library `config/entities/*.json`, then serves the **active version** of every `config/services/*.json` from its version file. A draft that was never published is not served. The repository's `config/` folder (not part of the image; use it with "Run it without Docker" or mount a copy) has three book examples, already published (version 1.0.0), that call the public [FakeRESTApi](https://fakerestapi.azurewebsites.net/index.html):

Calls under `/admin` need the admin login (see "Admin login" in `../README.md`). The examples use `-u admin:$PATCHBAY_PASSWORD`; set `PATCHBAY_PASSWORD` to your password first, or use the user and password from `MAPPER_ADMIN_USER` and `MAPPER_ADMIN_PASSWORD`.

```bash
# What is live
curl -u admin:$PATCHBAY_PASSWORD localhost:8080/admin/integrations

# create-book: POST with a JSON body (your field names → FakeRESTApi's)
curl -X POST localhost:8080/api/v1/integrations/create-book \
  -H 'Content-Type: application/json' \
  -d '{"title":"Patchbay","summary":"No-code API mapping","pages":42,"published":"2026-10-20T00:00:00Z"}'

# get-book: GET with query parameters; bookId goes into the external path /api/v1/Books/{id}
curl "localhost:8080/api/v1/integrations/get-book?bookId=7"

# list-books: the external response is a bare array; each item is mapped into {"books":[...]}
curl "localhost:8080/api/v1/integrations/list-books"

# Error shapes
curl -i "localhost:8080/api/v1/integrations/get-book?bookId=999999"   # 404 passed through, X-Integration-Error: remote
curl -i "localhost:8080/api/v1/integrations/get-book?bookId=seven"    # 400: bookId must be a whole number
curl -i -X POST localhost:8080/api/v1/integrations/create-book \
  -H 'Content-Type: application/json' -d '{"pages":"many"}'          # 400 listing every problem; FakeRESTApi is not called
```

## Publish, versions and rollback

The service file `config/services/<slug>.json` is the draft you edit; its `activeVersion` says which version is live. Publishing takes the current draft and writes an immutable, self-contained version file `config/services/<slug>.versions/<version>.json` with copies of the Input and Output definitions, so later edits to the draft or the library never change a published version, also after a restart.

```bash
curl -u admin:$PATCHBAY_PASSWORD -X POST localhost:8080/admin/integrations/get-book/publish                    # next minor: 1.0.0, 1.1.0, …
curl -u admin:$PATCHBAY_PASSWORD localhost:8080/admin/integrations/get-book/versions                           # stored versions and the live one
curl -u admin:$PATCHBAY_PASSWORD -X POST localhost:8080/admin/integrations/get-book/versions/1.0.0/activate    # make an older version live again
```

Order of a publish (R9): build and compile first; only if that works, write the version file, set `activeVersion`, swap the route. A publish that fails (broken link, missing secret, …) answers 400 with every problem and changes nothing. At startup an integration whose files are broken is skipped and listed under `broken` in `/admin/integrations`; the others start (R10). Every file is saved through a temp file and a rename, retried if Windows holds a lock, so a crash never leaves a half-written file (R4).

When editing a draft by hand, keep its `activeVersion` line: without it the integration is treated as never published at the next start.

## Moving integrations between environments

A package (D-28) is a ZIP laid out like the config folder: `manifest.json`, and per integration its draft (without `activeVersion`) and every version file, plus the payload definitions and Swagger configurations its draft uses. Connections and secrets are never in it.

```bash
curl -u admin:$PATCHBAY_PASSWORD -X POST localhost:8080/admin/api/export -H 'Content-Type: application/json' \
     -d '{"integrations":["get-book","create-book"]}' -o package.zip
curl -u admin:$PATCHBAY_PASSWORD -X POST localhost:8080/admin/api/import -H 'Content-Type: application/zip' --data-binary @package.zip
```

Import checks every file first and writes nothing if anything is wrong; the answer then lists every problem. A version that exists with other content is refused, because published versions never change. Versions already there are skipped, payload definitions and Swagger configurations are replaced, drafts are replaced but keep this environment's `activeVersion`. Nothing goes live: the answer lists per integration what this environment still needs (base URL, secret, header values) and which version was live where the package came from; `versions/<version>/activate` makes it live.

To configure a container without calls (D-31), mount a folder of packages and set `MAPPER_IMPORT_DIR` (`mapper.import-dir`). At startup, after the payload library and before serving, every `*.zip` in it is imported in file-name order with the same checks as above. Then each integration that has no `activeVersion` here is made active with the version that was live where its package came from. A package that fails is logged with every problem and skipped; a version that can't go live (missing secret or base URL) is listed under `broken` and tried again at the next start. Set connections and secrets with `MAPPER_URL_<ID>`, `MAPPER_HEADER_<ID>_<NAME>` and `MAPPER_SECRET_<SLUG>`.

```yaml
services:
  patchbay:
    environment:
      MAPPER_IMPORT_DIR: /import
      MAPPER_URL_FAKE_REST_API: https://fakerestapi.azurewebsites.net
    volumes:
      - ./packages:/import:ro
```

## Outbound auth and secrets

A service can authenticate to the external system:

```json
"auth": { "type": "bearer" }
"auth": { "type": "basic" }
"auth": { "type": "apiKey", "in": "header", "name": "X-Api-Key" }
"auth": { "type": "apiKey", "in": "query",  "name": "api_key" }
```

For `basic` (D-30) the secret is `username:password`, sent as `Authorization: Basic <base64>`; publishing with a secret that has no colon fails.

The secret is never stored in service or version files. It is read, in this order, from the environment variable `MAPPER_SECRET_<SLUG>` (slug in capitals, other characters as `_`), then from `config/secrets/<slug>.json` (`{"value": "..."}`, git-ignored). Publishing without a secret fails with a message naming both places.

The `create-user` example calls [GoREST](https://gorest.co.in) with a bearer token and is an unpublished draft. With your own GoREST token:

```bash
MAPPER_SECRET_CREATE_USER=<your token> java -jar target/engine-0.0.1-SNAPSHOT.jar --mapper.config-dir=config
curl -u admin:$PATCHBAY_PASSWORD -X POST localhost:8080/admin/integrations/create-user/publish
curl -X POST localhost:8080/api/v1/integrations/create-user -H 'Content-Type: application/json' \
  -d '{"fullName":"Mari Tamm","email":"mari.tamm.12345@example.com","gender":"female"}'
```

External calls time out after 10 seconds (`--mapper.outbound-timeout-ms` to change) and answer 504.

## Known behavior (by decision)

- **One editor at a time** (R3): two people or two tabs editing the same files is not supported; the last save wins.
- **Short gap while publishing** (R2, R7): while one integration's route is replaced (a few milliseconds), its callers get `503` with `Retry-After: 2`. Other integrations are never affected.
- **Shared payload definitions reach callers on republish** (R12): editing a definition in the library changes nothing live, but the next publish of *any* integration that uses it takes the new shape, without a warning.

## Tests

`mvn test` runs the routes end to end against a local fake of the Books API (no internet needed) and checks request/response mapping, input validation, constants, typed query parameters, lists, the shared link rules, header stripping, 400/415 errors, remote 404 pass-through, 502 when the remote is unreachable, 504 on timeout, publish/rollback with real version files, restart from version files, skipping broken files, secrets from env or file never being stored, and crash-safe saves.

## Payload definitions (`config/entities/*.json`)

Reusable shapes for what callers send (Input) and get back (Output). Each field has a stable `id`; links use the id, so renaming a field keeps its links.

```json
{
  "id": "new-book",
  "name": "New book",
  "fields": [
    { "id": "nb-title", "name": "title", "type": "text", "required": true },
    { "id": "nb-pages", "name": "pages", "type": "integer", "required": true },
    { "id": "nb-source", "name": "source", "type": "text", "value": "Created by Patchbay" }
  ]
}
```

Types: `text`, `number`, `integer`, `boolean`, `date`, `datetime`, `object` (with `fields`), `list` (with `items`). A field with a `value` is a constant: callers don't send it (if they do, it is ignored) and it can be linked like any other field. Input of a GET/DELETE integration must be flat (simple fields or lists of simple values), because it arrives as query parameters; values are converted to their types before validation.

## Service file (`config/services/*.json`)

```json
{
  "id": "get-book",
  "version": "1.0.0",
  "input": "book-ref",
  "output": "book-details",
  "target": { "connection": "fake-rest-api", "method": "GET", "path": "/api/v1/Books/{id}" },
  "request":  { "links": [ { "from": "br-bookId", "to": "path.id" } ] },
  "response": { "links": [ { "from": "id", "to": "bd-bookId" }, { "from": "title", "to": "bd-title" } ] }
}
```

Optional `"formats": { "request": "xml", "requestRoot": "Book", "output": "xml", "outputRoot": "bookRef" }` (D-24) sends the request body to the external system as XML under `<Book>` and returns XML under `<bookRef>` to callers; either part left out means JSON. The external answer is read as XML or JSON by its Content-Type. When reading XML the root element is dropped, elements and attributes become fields, repeated elements become lists (also a single one where a response link expects a list), and values get the Output definition's types.

A SOAP operation uses `"formats": { "request": "soap" }` and a `target.soap` block instead; see "SOAP services" below.

`target.connection` names the connection whose base URL is used (D-26): `MAPPER_URL_<ID>` if set, else `connections/<id>.json` with `{"baseUrl": "https://..."}`. The console names the connection after the Swagger configuration and writes that file with **Connection** under Configuration. `connections/` and `secrets/` belong to one environment; everything else in the folder can be moved between environments unchanged. A literal `target.baseUrl` still works in files from before connections; at startup, drafts that came from a Swagger configuration move it into that configuration's connection.

A connection can also send headers on every call to its system (D-27): `"headers": [{"name": "X-Client-Id", "value": "abc"}, {"name": "X-Tenant-Key", "secret": true}]` in `connections/<id>.json`. `MAPPER_HEADER_<ID>_<NAME>` wins over the value in the file. A secret header has no value in the connection file; it is in `secrets/connections/<id>.json` as `{"X-Tenant-Key": "..."}` and shows as `***` in the console, the Test drawer and the call log. If a mapped header has the same name, the connection's value is sent; the auth header wins over both.

Headers the contract doesn't declare can be added on the board; the draft lists them in `"addedHeaders": ["X-Correlation-Id"]` and links them like any other header (`"to": "headers.X-Correlation-Id"`).

Request links go from an Input field id to `path.`, `query.`, `headers.` or `body.` of the external call. Response links go from a dotted path in the external response to an Output field id. The caller uses the same HTTP method as `target.method`: a JSON body for POST/PUT/PATCH, query parameters for GET/DELETE.

## Lists

On the external side `[]` means "each item": `body.ADDRESSES[]` is the list, `body.ADDRESSES[].line1` a field in every item, and a bare `[]` is a response that is itself a list. On your side, list fields and the fields inside their items are referred to by field id as usual.

```json
{ "from": "p-addresses", "to": "body.ADDRESSES[]" },
{ "from": "p-street",    "to": "body.ADDRESSES[].line1" },
{ "from": "p-country",   "to": "body.ADDRESSES[].country" }
```

Rules (D-12), shared with the console in `../rules/link-cases.json`:
1. Link the list itself (list → list, same nesting level) before any field inside it.
2. Links inside a linked list run once per item.
3. After the list link, fields from outside the list may go into item fields; they are the same for every item.
4. Nested lists are linked level by level, outer first.
5. A field inside a nested list can't go to a less nested place.
6. A list can only go into a list, and a single value can't go into a list.
7. A single value can't go into an object, and an object can't go into a single value; link the object's fields instead. The type of an external end comes from the contract operation the draft was built from, so this is checked when a draft is published (a published version is compiled without its contract). An external path the contract doesn't describe, such as one inside a free-form object, is not checked.

A list linked without item links is copied as it is.

## Missing fields and null

A field the caller did not send is left out of the external request, not sent as `null`; an object is left out when nothing inside it was sent. An explicit `null` from the caller is passed on, so an update (PUT, POST) can reset a field. Responses mapped back into the Output follow the same rule.

Any broken link (unknown field id) or link that breaks these rules stops the version from publishing, and the error lists every problem at once (R8).

## SOAP services (WSDL contracts)

A SOAP service is imported from its WSDL 1.1 contract and published as an ordinary REST endpoint (D-30): callers POST JSON to `/api/v1/integrations/<slug>`, and the engine sends a SOAP envelope to the external system.

```bash
# By URL; schemas on the same scheme, host and port (e.g. ?xsd=1) are fetched too
curl -u admin:$PATCHBAY_PASSWORD -X POST localhost:8080/admin/api/contracts -H 'Content-Type: application/json' \
  -d '{"name":"Person registry","url":"https://registry.example/ws/person?wsdl"}'

# A ZIP with the WSDL and its XSD files, as base64; the WSDL is the one .wsdl file nearest the top
curl -u admin:$PATCHBAY_PASSWORD -X POST localhost:8080/admin/api/contracts -H 'Content-Type: application/json' \
  -d "{\"name\":\"Person registry\",\"zip\":\"$(base64 -w0 person-registry.zip)\"}"
```

A single WSDL file can also be sent as `content`, like an OpenAPI file; then any XSD it imports from a separate file is missing, which the import lists as a warning (affected fields become text). The engine reads WSDL and XSD with DTDs and external entities switched off. It follows `import`/`include` locations only inside the uploaded ZIP or on the contract URL's scheme, host and port; other locations are skipped with a warning, and a ZIP path that leaves the ZIP fails the import. The originals are kept in `contracts/<id>.wsdl/`, and a re-import replaces all of them. A ZIP may hold up to 500 files and 20 MB. Drafts calling a SOAP operation of a re-imported contract take its new SOAP details at once; their live versions keep the old ones until they are published again.

**Operations.** Every port with a SOAP 1.1 or 1.2 binding lists its operations with id `<port>/<operation>` (for example `PersonSoap/GetPerson` and `PersonSoap12/GetPerson` when a WSDL offers both versions). Only document/literal operations whose message parts refer to elements can be chosen; RPC style, encoded use and HTTP bindings are listed with the reason. The request tree is the children of the input element, the response tree the children of the output element.

**XSD.** Supported: elements and element references, named and anonymous complex types with `sequence` or `all`, `complexContent` extension (base type fields first), `simpleContent`, simple types and their restrictions (typed by the built-in they restrict), `minOccurs`/`maxOccurs`, `nillable`. Types: `int`, `long`, `short`, `integer` and the other integer types are integer; `decimal`, `float`, `double` are number; `boolean`; `date`; `dateTime` is datetime; everything else, including `base64Binary` (format `byte`), is text. `maxOccurs` above 1 makes a list; `minOccurs="0"` or `nillable` makes a field optional. A `choice` shows its first option, and `any`, attributes and recursive types are left out; each gives a warning.

**The target.** Choosing a SOAP operation sets `"method": "POST"`, `"formats": { "request": "soap" }` and a `soap` block on the target, which is copied into every version file so a published version never reads the contract again:

```json
"target": {
  "connection": "person-registry", "method": "POST", "path": "",
  "soap": {
    "version": "1.1",
    "action": "urn:GetPerson",
    "body": { "name": "GetPerson", "ns": "urn:example:person" },
    "address": "https://registry.example/ws/person",
    "request": {
      "ns": { "body.address.street": "urn:example:common" },
      "order": { "body": ["personCode", "includeHistory"] }
    },
    "response": { "element": "GetPersonResponse", "lists": ["person.phones[]"] }
  }
}
```

`request.ns` gives the namespace of each element path whose namespace differs from the body element's (`""` for an unqualified element); `request.order` the XSD sequence order of an object's children; `response.lists` the repeated elements of the answer. `address` is the WSDL's port address, used only to prefill the connection's base URL. The request format stays `soap` for a SOAP operation and can't be chosen for any other.

**The call.** Request links go to `body.` as usual (`body.personCode`, `body.address.city`). The engine writes the mapped body inside the body element, every element in its namespace and in sequence order; a field the caller left out is left out, an explicit `null` is sent as `xsi:nil="true"`, and a list is written as repeated elements. SOAP 1.1 is sent as `text/xml; charset=utf-8` with `SOAPAction: "<action>"`, SOAP 1.2 as `application/soap+xml; charset=utf-8; action="<action>"`. The call goes through the same HTTP step as REST: connection, connection headers, auth, cookies, timeout, call log and the Test drawer, and the external system is called exactly once.

**The answer.** The first element in the Body is read by local name (prefixes don't matter), like an XML answer (D-24): repeated elements are lists, also when one occurs once, and values get the Output definition's types. Response links start below that element (`person.firstName`).

**Faults.** A SOAP 1.1 or 1.2 Fault, on any status, answers the caller with the engine's JSON error and `X-Integration-Error: remote`, with the remote status, or 502 when the remote status was below 400:

```json
{"error":"remote-fault","integration":"find-person","detail":"Unknown person",
 "fault":{"code":"soap:Client","reason":"Unknown person","detail":{"error":{"code":"404"}}}}
```

An error answer that is not a SOAP Fault (an HTML 503 page, say) passes through unchanged, as for REST. A successful answer that is not a SOAP envelope is a mapping error (500).

Packages carry a WSDL contract's folder with the integration, so the other environment needs no access to the WSDL's URL.

## Not built yet

XML from callers, XML namespaces outside SOAP, transforms and conditions (TODO-T1), Git sync of packages, a CI build of the image. For SOAP (D-30): WSDL 2.0, RPC style, SOAP headers in mappings, WS-Security, MTOM attachments, callers sending XML or SOAP, and publishing an integration as a SOAP service.
