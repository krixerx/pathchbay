# Spike E1: runtime routes on platform-http (eng review R7)

Throwaway code to answer three questions before the engine is built. Not the engine.

Stack: Camel 4.18.5 (`camel-spring-boot-bom`), Spring Boot 3.5.16 (the version `camel-dependencies-4.18.5` is built against), `camel-platform-http-starter`, `camel-yaml-dsl-starter`, run on OpenJDK 25 with `release 21`.

Run: `mvn test` in this folder. Findings print with the prefix `SPIKE:`.

## Results (2026-10-20, local laptop, 4–6 load threads)

| Question | Result |
|---|---|
| Q1 Add a templated route with a new REST path after startup | Works. `camel.addRouteFromTemplate("int-alpha", "postTemplate", params)` registers `POST /api/v1/integrations/alpha` at runtime; first call returns 200. |
| Q3 YAML `routeTemplate` + `templatedRoute` keys at runtime | Work. Loaded with `PluginHelper.getRoutesLoader(camel).loadRoutes(...)`; keys `routeTemplate.id`, `parameters`, `templatedRoute.routeTemplateRef`, `routeId`, `parameters[].name/value`. |
| Q2 Replace one integration's route under load | Swap (stop, remove, add) takes 4–23 ms. The other integration saw **only 200** in every run (NFR-003 holds). The replaced integration saw a small number of errors during swaps: run 1 (one swap): 200 × 3,492, 503 × 2. Run 2 (20 swaps): 200 × 34,436, **500 × 155, 503 × 43** (≈0.6 % of calls). No 404s. |

What the errors look like (not what R2 promised):
- `503`: Spring Boot's HTML "Whitelabel Error Page", `connection: close`, **no `Retry-After`**.
- `500`: empty body, `content-type: text/plain`, `connection: close`.

A later ungated run also produced one Spring JSON `404` during a swap.

## Fix chosen in R7 (D19 A) and verified: `IntegrationGate`

A Spring `ErrorController` on `/error` did **not** help: the 500s and most 503s are written by Camel's platform-http consumer directly, so they never reach `/error` (tried, removed).

What works is keeping callers out of a route while it is replaced. `IntegrationGate` is a servlet filter on `/api/v1/integrations/**`:
1. Each call counts itself in-flight for its slug, then checks whether the slug's gate is closed.
2. If closed: it leaves the count and answers JSON `503` with `Retry-After: 2`.
3. `gate.swap(slug, …)` closes the gate, waits until the slug's in-flight count is 0, then stops, removes and re-adds the route, then reopens.

Result over 20 gated swaps under load (two runs): replaced integration 200 × 40,015 / 39,375 and **only** JSON 503 with `Retry-After: 2` (248 / 226), **zero 500, zero HTML, zero 404**. Other integration: only 200. Swap including the drain: ≤ 5 ms.

## What this means for R7

- Per-integration routes **can** be added and replaced at runtime on platform-http + Spring Boot, and other integrations are untouched; the outside voice's main concern is refuted.
- Without a gate, callers of the replaced integration get a mix of empty 500, HTML 503 and occasionally 404 during the swap.
- With `IntegrationGate`, the R2 contract holds exactly: JSON 503 with `Retry-After: 2`, nothing else.

## Known spike issue

Surefire once printed "Surefire is going to kill self fork JVM … 30 seconds after System.exit(0)": the JVM was slow to stop after the tests finished (all passed). Probably Camel's graceful shutdown timeout; not investigated, not relevant to the findings.
