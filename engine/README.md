# Integration Mapper engine

Spring Boot 4.1.1, Apache Camel 4.23.0, Java 21. Plan: `../docs/slice-plan.md` (tasks E1–E5 done). No console yet: integrations are JSON files, published with admin calls.

## Run it locally

```bash
cd engine
mvn package
java -jar target/engine-0.0.1-SNAPSHOT.jar --mapper.config-dir=config
```

Startup loads the payload library `config/entities/*.json`, then serves the **active version** of every `config/services/*.json` from its version file. A draft that was never published is not served. The three book examples are already published (version 1.0.0) and call the public [FakeRESTApi](https://fakerestapi.azurewebsites.net/index.html):

```bash
# What is live
curl localhost:8080/admin/integrations

# create-book: POST with a JSON body (your field names → FakeRESTApi's)
curl -X POST localhost:8080/api/v1/integrations/create-book \
  -H 'Content-Type: application/json' \
  -d '{"title":"Integration Mapper","summary":"No-code API mapping","pages":42,"published":"2026-10-20T00:00:00Z"}'

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
curl -X POST localhost:8080/admin/integrations/get-book/publish                    # next minor: 1.0.0, 1.1.0, …
curl localhost:8080/admin/integrations/get-book/versions                           # stored versions and the live one
curl -X POST localhost:8080/admin/integrations/get-book/versions/1.0.0/activate    # make an older version live again
```

Order of a publish (R9): build and compile first; only if that works, write the version file, set `activeVersion`, swap the route. A publish that fails (broken link, missing secret, …) answers 400 with every problem and changes nothing. At startup an integration whose files are broken is skipped and listed under `broken` in `/admin/integrations`; the others start (R10). Every file is saved through a temp file and a rename, retried if Windows holds a lock, so a crash never leaves a half-written file (R4).

When editing a draft by hand, keep its `activeVersion` line: without it the integration is treated as never published at the next start.

## Outbound auth and secrets

A service can authenticate to the external system:

```json
"auth": { "type": "bearer" }
"auth": { "type": "apiKey", "in": "header", "name": "X-Api-Key" }
"auth": { "type": "apiKey", "in": "query",  "name": "api_key" }
```

The secret is never stored in service or version files. It is read, in this order, from the environment variable `MAPPER_SECRET_<SLUG>` (slug in capitals, other characters as `_`), then from `config/secrets/<slug>.json` (`{"value": "..."}`, git-ignored). Publishing without a secret fails with a message naming both places.

The `create-user` example calls [GoREST](https://gorest.co.in) with a bearer token and is an unpublished draft. With your own GoREST token:

```bash
MAPPER_SECRET_CREATE_USER=<your token> java -jar target/engine-0.0.1-SNAPSHOT.jar --mapper.config-dir=config
curl -X POST localhost:8080/admin/integrations/create-user/publish
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
    { "id": "nb-source", "name": "source", "type": "text", "value": "Created by Integration Mapper" }
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
  "target": { "baseUrl": "https://fakerestapi.azurewebsites.net", "method": "GET", "path": "/api/v1/Books/{id}" },
  "request":  { "links": [ { "from": "br-bookId", "to": "path.id" } ] },
  "response": { "links": [ { "from": "id", "to": "bd-bookId" }, { "from": "title", "to": "bd-title" } ] }
}
```

Request links go from an Input field id to `path.`, `query.`, `headers.` or `body.` of the external call. Response links go from a dotted path in the external response to an Output field id. The caller uses the same HTTP method as `target.method`: a JSON body for POST/PUT/PATCH, query parameters for GET/DELETE.

## Lists

On the external side `[]` means "each item": `body.ADDRESSES[]` is the list, `body.ADDRESSES[].line1` a field in every item, and a bare `[]` is a response that is itself a list. On your side, list fields and the fields inside their items are referred to by field id as usual.

```json
{ "from": "p-addresses", "to": "body.ADDRESSES[]" },
{ "from": "p-street",    "to": "body.ADDRESSES[].line1" },
{ "from": "p-country",   "to": "body.ADDRESSES[].country" }
```

Rules (D-12), shared with the future console in `../rules/link-cases.json`:
1. Link the list itself (list → list, same nesting level) before any field inside it.
2. Links inside a linked list run once per item.
3. After the list link, fields from outside the list may go into item fields; they are the same for every item.
4. Nested lists are linked level by level, outer first.
5. A field inside a nested list can't go to a less nested place.
6. A list can only go into a list, and a single value can't go into a list.

A list linked without item links is copied as it is. A missing source list gives `null`.

Any broken link (unknown field id) or link that breaks these rules stops the version from publishing, and the error lists every problem at once (R8).

## Not in the engine yet

The console (publishing and editing are files plus the admin calls above), the Docker image, JSON ↔ XML (TODO-T4), extra outbound headers (TODO-T3), transforms and conditions (TODO-T1).
