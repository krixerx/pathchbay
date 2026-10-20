# Integration Mapper engine (walking skeleton)

Spring Boot 4.1.1, Apache Camel 4.23.0, Java 21. Plan: `../docs/slice-plan.md` (Next Step 3). No console yet: integrations are hand-written JSON files.

## Run it locally

```bash
cd engine
mvn package
java -jar target/engine-0.0.1-SNAPSHOT.jar --mapper.config-dir=config
```

Startup publishes every `config/services/*.json`. The two examples call the public [FakeRESTApi](https://fakerestapi.azurewebsites.net/index.html):

```bash
# What is live
curl localhost:8080/admin/integrations

# create-book: POST with a JSON body (your field names → FakeRESTApi's)
curl -X POST localhost:8080/api/v1/integrations/create-book \
  -H 'Content-Type: application/json' \
  -d '{"title":"Integration Mapper","summary":"No-code API mapping","pages":42,"published":"2026-10-20T00:00:00Z"}'

# get-book: GET with query parameters; bookId goes into the external path /api/v1/Books/{id}
curl "localhost:8080/api/v1/integrations/get-book?bookId=7"

# Error shapes
curl -i "localhost:8080/api/v1/integrations/get-book?bookId=999999"   # 404 passed through, X-Integration-Error: remote
curl -i  localhost:8080/api/v1/integrations/get-book                  # 400, missing path parameter
```

## Tests

`mvn test` runs the routes end to end against a local fake of the Books API (no internet needed) and checks request/response mapping, header stripping, query → path, 400/415 errors, remote 404 pass-through, 502 when the remote is unreachable, and that a version that doesn't compile never replaces the live one.

## Service file (skeleton subset)

```json
{
  "id": "get-book",
  "version": "1.0.0",
  "target": { "baseUrl": "https://fakerestapi.azurewebsites.net", "method": "GET", "path": "/api/v1/Books/{id}" },
  "request":  { "links": [ { "from": "bookId", "to": "path.id" } ] },
  "response": { "links": [ { "from": "id", "to": "bookId" }, { "from": "title", "to": "title" } ] }
}
```

Request link targets start with `path.`, `query.`, `headers.` or `body.`. The caller uses the same HTTP method as `target.method`: a JSON body for POST/PUT/PATCH, query parameters for GET/DELETE.

## Not in the skeleton yet

Payload definitions and input validation, lists (D-12), constants (R11), outbound auth and secrets, version files and rollback, outbound timeout setting, the console, Docker image.
