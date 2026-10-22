# Integration Mapper

Connect your own data model to an external REST API without writing code. Import the API's OpenAPI (Swagger) file, link your fields to theirs in the browser, and publish: the result is a live, versioned REST endpoint, served by the engine in the same container.

Working name; the product name is still open (spec OQ-006).

## What you need

| To | Install |
|---|---|
| Run it with Docker | Docker (Docker Desktop on Windows and macOS) |
| Build and run it without Docker | Java 21 or newer, Maven 3.9 or newer, Node.js 24 with npm |

No database or other service is needed. Everything is stored as JSON files in one folder.

## Run it with Docker

```bash
cd engine
docker build -t integration-mapper .
docker run -p 8080:8080 -v mapper-config:/config integration-mapper
```

Open **http://localhost:8080**. Your work is saved in the named volume `mapper-config` and survives restarts and new images. To use a folder on your computer instead, mount it (`-v ./mapper-config:/config`); on Linux create it first and give it to the container user: `mkdir mapper-config && sudo chown 10001 mapper-config`.

With variables (see the table below):

```bash
docker run -p 8080:8080 -v mapper-config:/config \
  -e INBOUND_API_KEY=choose-a-long-random-value \
  -e MAPPER_SECRET_CREATE_USER=your-gorest-token \
  integration-mapper
```

## Run it without Docker

```bash
cd engine/console
npm install
npm run build          # builds the console into engine/src/main/resources/static
cd ..
mvn clean package      # runs the tests and builds target/engine-0.0.1-SNAPSHOT.jar
java -jar target/engine-0.0.1-SNAPSHOT.jar --mapper.config-dir=config
```

Open **http://localhost:8080**. `config/` in the repository has three published examples that call the public [FakeRESTApi](https://fakerestapi.azurewebsites.net/index.html); use another folder to start empty (`--mapper.config-dir=my-config`, created on the first save).

Working on the console with hot reload: keep the engine running, then in `engine/console` run `npm run dev` and open http://localhost:5173 (API calls go to the engine on 8080).

Tests: `mvn test` in `engine/` (no internet needed) and `npm test` in `engine/console/`.

## Variables

None are required. Set them as environment variables (`-e` with Docker), or for the first three also as a command-line option of the jar.

| Variable | Default | What it does |
|---|---|---|
| `MAPPER_CONFIGDIR` (option `--mapper.config-dir`) | `config` (in Docker: `/config`) | Folder with all configuration: payload definitions, contracts, integrations, versions and secrets. |
| `SERVER_PORT` (option `--server.port`) | `8080` | Port of the console, the admin API and the published integrations. |
| `MAPPER_OUTBOUNDTIMEOUTMS` (option `--mapper.outbound-timeout-ms`) | `10000` | How long a call to an external system may take, in milliseconds; after that the caller gets `504`. |
| `INBOUND_API_KEY` | not set | When set, every call to a published integration, the console and the admin API must send this value in the `X-API-Key` header, otherwise `401`. The console asks for it once per browser tab. Not set: nothing is checked, which is meant for a closed Docker network only. |
| `MAPPER_SECRET_<SLUG>` | not set | The token or API key an integration sends to its external system, for example `MAPPER_SECRET_CREATE_USER` for the integration `create-user` (slug in capitals, other characters as `_`). It wins over a secret typed on the integration's Settings page. |
| `MAPPER_CALL_LOG` | `on` | `on` keeps the last 100 live calls of each integration in memory, with what the caller sent, the request sent out, the answer and the mapped output, shown under **Calls** on the board. `off` keeps none of that; each call still writes one line without data to the log. |

## Security in short

- Without `INBOUND_API_KEY`, anyone who can reach the port can use the console and every integration, including the stored credentials of the external systems. Set it whenever the port is reachable from outside a closed network.
- Secrets typed on a Settings page are stored **unencrypted** in `<config>/secrets/<slug>.json`, readable only by the engine's user on Linux and in Docker (Windows does not enforce it). Use `MAPPER_SECRET_<SLUG>` to keep them out of the folder.
- Headers that callers send (including `X-API-Key` and `Authorization`) never reach the external system.
- The call log holds real payloads, which may include personal data, and anyone with console access can see them. Auth values are shown as `***`. Set `MAPPER_CALL_LOG=off` where that is not acceptable.

## How to build an integration

1. **Configuration:** import the external system's Swagger or OpenAPI file (upload or URL). Every integration can use it.
2. **Payload definitions** (optional): describe what your callers send and get back, by hand or from a pasted JSON sample.
3. **Integrations → New integration**, then on its board pick the Input, the Output, the Swagger configuration and the operation. Leave Input or Output empty to have it created from the operation, already linked.
4. **Map:** click a field on the left, then the field on the right it goes into; a line connects them. Red lines can't be published; amber warnings can. **Test** runs a sample through the same code as a live call.
5. **Publish:** shows the live URL and a ready `curl` command. Earlier versions can be made active again.
6. **Calls:** every live call, step by step, with where it stopped if it failed. `docker logs` shows one line per call.

Base URL, authentication and the secret are on the integration's **Settings** page.

## More

- `engine/README.md`: file formats, admin calls, lists, error shapes, known behavior.
- `docs/slice-plan.md`: the plan and every design decision (D-…, R-…); `docs/integration-mapper-spec.md`: the long-term spec.
- `DESIGN.md`: the console's design tokens and rules.
