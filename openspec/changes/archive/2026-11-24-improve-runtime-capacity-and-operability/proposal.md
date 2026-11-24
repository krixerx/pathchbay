# Proposal

## Why

The 2026-11-24 engine review (spring-boot-skills checklists `async-execution`, `production-observability`, `configuration-properties` and `resilience-retry`) found that the hot path is narrower than NFR-001 intends and hard to operate:

- **All integration calls share 8 threads.** Camel's platform-http consumer runs each exchange on Boot's `applicationTaskExecutor`, which has 8 core threads and an unbounded queue, because virtual threads are not enabled. This was measured on the running container: 9 parallel calls to a 0.5 s stub took 1.1 s, and the container logs show only `task-1` to `task-8`. Eight slow calls to one external system stall every other integration. NFR-001 and CLAUDE.md already plan virtual threads.
- **The gate may not drain in-flight calls (unconfirmed).** Because the exchange runs asynchronously, `IntegrationGate`'s `finally` probably decrements the count when the request goes async, not when the call ends, so `swap()` would see zero almost at once (R7). This comes from static analysis only. Camel's graceful route stop may cover part of it, and no test exercises `swap`. While it waits, `swap()` also busy-spins a core for up to 30 s, and after the deadline it swaps silently. `inFlight` keeps an entry for every slug any caller invents.
- **Failures leave no trace.** `IntegrationProcessors` has no logger. A DataSonnet error or an unexpected exception becomes a 500 or 502 whose internal message goes to the caller, and the only log line is the call summary. With `MAPPER_CALL_LOG=off`, or after 100 newer calls, the cause is gone.
- **No health or readiness.** There is no actuator. Tomcat accepts calls while `ConfigLoader` is still loading integrations, so callers get 404 for live integrations during startup.
- **Settings are scattered.** `mapper.max-blob-bytes` is read with its default repeated in four or five places, and `mapper.outbound-timeout-ms` in two. `TestRunner` hardcodes 10 s next to the property. Nothing validates the values. The three 10 s outbound timeouts add up to a worst case of about 30 s, not 10 s.

## What Changes

- Enable virtual threads (`spring.threads.virtual.enabled=true`) for the platform-http executor, and set the MVC async timeout above the outbound budget (three outbound timeouts plus 30 s).
- First, prove or disprove the gate-drain gap with a test that publishes while a slow call is in flight. If the gap is real, count completion where the exchange ends (the route's completion, or an `AsyncListener`). Either way, wait without spinning, log a warning when the deadline passes, count only registered slugs, and remove a slug's entry when it is deleted.
- Log a WARN with the exception classes and stack frames, but no exception messages (they can hold payload values), for unexpected 500/502 failures. Callers get a generic detail and the request id; a sentence Patchbay wrote itself is still shown.
- Add `spring-boot-starter-actuator`, exposing only `health` with liveness and readiness (open without the key), so readiness turns on after `ConfigLoader` has loaded. Add a request id (`X-Request-Id`, echoed, logged, and added to the error JSON of the unexpected failures).
- One `@ConfigurationProperties("mapper")` record, checked in its constructor, replaces the `mapper.*` `@Value` lookups. The property and environment variable names stay the same. The outbound deadline is documented as a total, or enforced as one.

The stored JSON and the existing error JSON fields do not change. The request id is an added field.

Out of scope: Micrometer metrics per integration and Prometheus (a follow-up once actuator is in), tracing, and moving integrations off platform-http.

## Capabilities

### New Capabilities

- `engine-operability`: capacity, startup readiness, failure logging and settings of the running engine.

### Modified Capabilities

None.

## Impact

- Engine: `application.properties`, `pom.xml` (actuator), `web/IntegrationGate`, `runtime/IntegrationProcessors`, `runtime/IntegrationPublisher`, `runtime/ConfigLoader`, `runtime/TestRunner`, `runtime/CallLog`, `web/OutboundHttpConfig`, `web/InboundHttpConfig`, `web/InboundKeyFilter` (probes open), and new `config/MapperProperties` and `web/RequestIdFilter`.
- `engine/README.md` and `engine/Dockerfile`: the health endpoint, a `HEALTHCHECK`, and `-XX:MaxRAMPercentage` / `-XX:+ExitOnOutOfMemoryError`.
- PathchbayTestAndMock: a concurrency test (16 parallel calls to a slow stub finish in about one stub delay), a readiness check in `docker-compose.yml`, and a publish-under-load test. The existing 198 tests must still pass.
- New dependency: `spring-boot-starter-actuator` (Apache-2.0, NFR-009).
