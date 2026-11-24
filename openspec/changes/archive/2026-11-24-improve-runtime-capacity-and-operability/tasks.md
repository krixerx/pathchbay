# Tasks

## 1. Capacity

- [x] 1.1 Add a test where parallel calls hit a stub that answers after 0.5 s (`VersionLifecycleTest.parallelSlowCallsAreNotServedInBatches`, 24 calls, under 1.2 s); verified failing on the current code
- [x] 1.2 Set `spring.threads.virtual.enabled=true`, and set the MVC async timeout to three outbound timeouts plus 30 s (`InboundHttpConfig`; a property can't follow `mapper.outbound-timeout-ms`). Verify that 1.1 passes and `mvn test` is green

## 2. Gate

- [x] 2.1 `VersionLifecycleTest.aCallInFlightDuringPublishFinishesOnItsVersion` publishes 1.1.0 while a 0.5 s call to 1.0.0 is in flight. Result: the call finishes on 1.0.0, publish waits for it, later calls get 1.1.0. So callers saw no gap; Camel's graceful route stop covered for the gate, whose count dropped when the filter returned rather than when the call ended (not measured on the old code, since the count was not observable there)
- [x] 2.2 The gate now also runs on the async dispatch and counts a call out only when it ends (`gate.inFlight(slug)` is 1 while the call waits), counts only published integrations, waits in 5 ms sleeps instead of spinning, logs a warning when it stops waiting after 30 s, and forgets a slug that is no longer live

## 3. Failures and readiness

- [x] 3.1 `IntegrationProcessors` logs a mapping error or a failure of unknown cause at WARN with the exception classes and stack frames, but no exception messages (they can hold payload values), and the request id. The caller gets a generic detail and `requestId`; the call log keeps the real message for the console. A sentence Patchbay wrote itself (a `MappingException` without a cause, such as "the answer is not a SOAP envelope") is still shown to the caller. Verified with `VersionLifecycleTest.anUnexpectedFailureIsLoggedAndTheCallerGetsTheRequestId`
- [x] 3.2 Add `spring-boot-starter-actuator` with only `health` exposed and the liveness/readiness probes on; the probes are open without `INBOUND_API_KEY`. `StartupImportTest.readinessComesAfterTheIntegrationsAreLoaded` records what was live when readiness turned on (the startup integrations were) and checks that `/actuator/env`, `/beans` and `/info` are 404
- [x] 3.3 `web/RequestIdFilter`: the caller's `X-Request-Id` when it is a plain token of up to 64 characters, otherwise a new UUID; echoed, in the MDC, passed to the route and in the call log line. It is added to the error JSON only for the unexpected failures of 3.1, because exact-match tests and callers compare the other error bodies field by field

## 4. Settings

- [x] 4.1 `config/MapperProperties` (`@ConfigurationProperties("mapper")`) replaces every `mapper.*` `@Value`, including `TestRunner`'s fixed 10 s. It checks values in its constructor rather than with Bean Validation, so no new dependency. `MapperPropertiesTest` binds the README's environment variable names (including `MAPPER_IMPORT_DIR` with its underscore) and shows that a negative `max-blob-bytes` stops binding naming the setting. `INBOUND_API_KEY` and `MAPPER_CALL_LOG` are not `mapper.*` properties and keep their `@Value`
- [x] 4.2 Document in `README.md` that the outbound timeout applies to pool, connect and answer separately (up to three times in the worst case)

## 5. Image and end-to-end

- [x] 5.1 `engine/Dockerfile`: `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"` and a `HEALTHCHECK` on readiness
- [x] 5.2 PathchbayTestAndMock: `ErrorHandlingTest.parallelSlowCallsAreNotServedInBatches` and `theRequestIdComesBackWithAnError`; compose's health check uses readiness. Publish under load is covered by the engine test of 2.1 only. All 210 tests pass, twice

Engine: `mvn test` 235 tests pass.
