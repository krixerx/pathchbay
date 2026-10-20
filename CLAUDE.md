# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Current state

The engine lives in `engine/` (Java 21, Spring Boot 4.1.1, Apache Camel 4.23.0, Maven). There is no console yet. Commands, run from `engine/`:

- `mvn test` runs all tests; `mvn test -Dtest=ListMappingTest` runs one class, `-Dtest=ListMappingTest#nestedListsAreMappedLevelByLevel` one method. Tests need no internet (a local fake of FakeRESTApi's Books endpoints).
- `mvn package && java -jar target/engine-0.0.1-SNAPSHOT.jar --mapper.config-dir=config` starts the engine on port 8080 with the example integrations, which call the public FakeRESTApi. `engine/README.md` has the curl calls and the config file formats.

`rules/link-cases.json` is the shared table of linking rules (D-12, R5): the engine's `LinkCasesTest` runs every case, and the console must run the same file. Change a rule there first.

- `docs/integration-mapper-spec.md`: the product spec, the long-term source of truth for requirements.
- `docs/slice-plan.md`: the reviewed build plan for the first vertical slice (office hours, design review and eng review, 2026-10-20). **Where it differs from the spec, the slice plan wins for current work.** Its UI decisions are D-1…D-20, its engineering decisions are R1…R12 in the "Decision ledger", deferred work is TODO-T1…T4, and the build order is "Eng Review: Implementation Tasks" (E1…E5).
- `docs/test-plan.md`: the tests the slice must pass.

Anything in neither document is out of scope until it is added as a requirement. One engineering decision is still open: R7 (one fixed route vs one Camel route per integration) waits for the routing spike, task E1, which comes before other engine work.

Main ways the slice differs from the spec: no React Flow canvas (two trees, source-then-target linking); no transforms or conditions (constants live in payload definitions); payload definitions are a reusable library; one Camel route template per HTTP method; GET/DELETE integrations take flat query parameters; red or broken links block publish; unit tests only (JUnit 5 + WireMock, Vitest).

## Spec conventions that apply to all work

- Requirements have stable IDs: `FR-xxx`, `NFR-xxx`, `ADR-xxx`, `OQ-xxx`, `TODO-xxx`. Commit messages and tests MUST reference the requirement ID they implement.
- MUST / SHOULD / MAY follow RFC 2119; FR priorities use MoSCoW.
- "Integration Mapper" is a working name (OQ-006 is open). Keep it search-and-replace safe: no derived abbreviations baked into identifiers.

## Planned architecture (from the spec)

Integration Mapper lets an analyst import an external contract (OpenAPI 2/3, API Blueprint, WSDL), define internal entities, map fields visually in both directions, and publish the mapping as a versioned REST endpoint run by Apache Camel. One inbound call always maps to exactly one outbound operation; there is no orchestration in v1 (OQ-001).

Planned stack: Java 21, Spring Boot 3, Apache Camel 4 with `camel-datasonnet`, `camel-cxf-soap` for SOAP, swagger-parser; console in React + TypeScript + Vite with React Flow (`@xyflow/react`) for the mapping board (ADR-001, ADR-002). All dependencies must be OSI-approved and Apache-2.0 compatible (NFR-009).

Key ideas that span several parts of the system:

- **One image, two roles (ADR-003).** One Spring Boot app holds the engine, the admin API and the built console. `MODE=engine|console|all` and `CONSOLE_ENABLED=false` switch roles. The engine must work with the console and admin API completely absent.
- **Files are the source of truth (ADR-004).** Configuration is plain JSON, one file per item, all listed from `config/mapper.json` (entities, contracts, services, security, consumers). Unlisted files are ignored. Every file has a `$schema` and is validated on load. Secrets and environment-specific URLs appear only as `{env:NAME}` or `{file:path}` substitutions, never as literals. A database is optional and holds only rebuildable or runtime data (index, users, audit, metrics). IDs are slugs, not DB IDs.
- **Mapping model is the frontend/backend contract.** The console edits a JSON mapping model (`links` with `transforms` chains and `condition`, `blockConditions`, `callCondition`, `schemaVersion`). A generator turns it into DataSonnet scripts and a Camel YAML route. Generated DataSonnet and routes are never stored as configuration. Conditions skip fields by emitting a `null` computed key.
- **Publish pipeline.** Validate, generate in memory, compile-check, write an immutable snapshot `services/{slug}.versions/{semver}.json` with SHA-256, then hot-load only route ID `int-{slug}-v{major}` without touching other routes (NFR-003). Major versions run in parallel under `/api/v{major}/integrations/{slug}`; minor and patch replace in place.
- **Engine hot path (NFR-001, performance principles).** No DB, file or config reads per request; DataSonnet compiled once per published version; virtual threads; pooled outbound clients; async audit logging.
- **Security surfaces are separate.** Console (basic auth, `viewer`/`editor` roles, own port 8081), inbound consumers (`X-API-Key`, hashed), outbound per-contract profiles. Env values always win over console-entered secrets; secrets never reach packages, logs or browser storage.
