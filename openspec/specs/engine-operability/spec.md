# engine-operability Specification

## Purpose

Defines the capacity, startup readiness, failure logging and settings of the running engine.

## Requirements

### Requirement: Slow external systems do not stall other integrations
The engine SHALL NOT cap the number of integration calls in progress at a fixed small thread pool. A slow external call SHALL NOT delay calls to other integrations.

#### Scenario: Parallel slow calls
- **WHEN** 16 callers call an integration whose external system answers after 0.5 s, all at the same time
- **THEN** all 16 calls finish within about one external delay, not in batches

### Requirement: Publish lets in-flight calls finish
When an integration is republished, a call that has already started SHALL finish on the version it started on. The swap SHALL wait for in-flight calls without busy-waiting, and SHALL log a warning when it stops waiting.

#### Scenario: Publish during a slow call
- **WHEN** a call to `x` is waiting for its external system and version 1.1.0 of `x` is published
- **THEN** that call completes with the 1.0.0 mapping, and calls that start after the swap use 1.1.0

#### Scenario: Unknown slugs are not tracked
- **WHEN** callers send requests to many integration paths that are not published
- **THEN** each gets 404, and the engine keeps no per-slug state for them

### Requirement: Readiness reflects loaded integrations
The engine SHALL report readiness only after every active integration has been loaded at startup, and SHALL expose no actuator endpoint other than health.

#### Scenario: Startup
- **WHEN** the engine has started its web server but is still loading integrations
- **THEN** the readiness health check reports down

### Requirement: Unexpected failures are logged, not shown
For an unexpected failure during a call, the engine SHALL log a warning with the stack trace and without payload data, and SHALL answer the caller with a generic detail and a request id.

#### Scenario: Mapping error at runtime
- **WHEN** a published mapping throws during a call
- **THEN** the engine logs a warning with the stack trace and the request id, and the caller's error JSON holds the request id but not the internal message

### Requirement: Settings are validated at startup
The engine SHALL read its `mapper.*` settings from one validated place, and SHALL refuse to start with an invalid value.

#### Scenario: Negative file size limit
- **WHEN** the engine starts with `mapper.max-blob-bytes=-1`
- **THEN** startup fails with a message that names the setting
