# /api/hello records a visit in the visitor backend — 2026-10-30

Branch: `feature/hello-counts-visits` (based on `main` at `3ac1254`, after PR #24 added the visitor backend)

Goal: every call to `GET /api/hello` on the facade backend sends `POST /api/visits` to the visitor backend, so the visit counter in Valkey goes up by one per greeting.

## How it works

```
browser ──GET /api/hello──▶ facade (8080) ──POST /api/visits──▶ visitor (8081) ──INCR fs-web:visits──▶ Valkey
                              │                                   │
                              └──── same trace (traceparent) ─────┘
```

| Step | What happens                                                                                                                              |
|------|-------------------------------------------------------------------------------------------------------------------------------------------|
| 1    | `HelloController.hello()` logs the call and calls `VisitorClient.recordVisit()`                                                           |
| 2    | `VisitorClient` sends `POST {fsweb.visitor.base-url}/api/visits` with a `RestClient` and reads `{"count": n}`                             |
| 3    | The greeting is returned. The response body is unchanged (`Hello, <name>!`), so the frontend needs no change                              |
| 4    | If the visitor backend is down, slow or returns an error, `recordVisit()` logs a `WARN` and returns empty; `/api/hello` still answers 200 |

The call is synchronous: `/api/hello` waits for the visitor backend, at most the connect timeout (1 s) plus the read timeout (2 s). With the visitor backend stopped, the connection is refused immediately (about 20 ms in the test below).

## Design choices

| Choice                                          | Reason                                                                                                                                                              |
|-------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `RestClient` (`spring-boot-starter-restclient`) | Boot 4 moved RestClient auto-configuration out of the web starter. The auto-configured `RestClient.Builder` has observation applied, so trace context is propagated |
| Direct backend-to-backend URL                   | The facade calls the visitor backend at `fsweb.visitor.base-url`, not through the Vite proxy or the production reverse proxy                                        |
| Failure does not fail `/api/hello`              | The counter is secondary to the greeting; a missing visit is logged instead of returning 5xx                                                                        |
| Short timeouts                                  | `/api/hello` waits for the call; 1 s connect / 2 s read bound the extra latency                                                                                     |
| Facade has its own `VisitCount` record          | No shared module between backends yet; the record is one field and matches the visitor's JSON                                                                       |

## Configuration (facade)

| Property                        | Default (`application.yml`) | `prod` profile                         |
|---------------------------------|-----------------------------|----------------------------------------|
| `fsweb.visitor.base-url`        | `http://localhost:8081`     | `${VISITOR_URL:http://localhost:8081}` |
| `fsweb.visitor.connect-timeout` | `1s`                        | inherited                              |
| `fsweb.visitor.read-timeout`    | `2s`                        | inherited                              |

Bound to the `VisitorProperties` record (`@ConfigurationProperties("fsweb.visitor")`).

## Files changed

| File                                                                                  | Change                                                                 |
|---------------------------------------------------------------------------------------|------------------------------------------------------------------------|
| `backends/facade/pom.xml`                                                             | Added `spring-boot-starter-restclient`; description updated            |
| `backends/facade/src/main/java/org/fsweb/facade/client/VisitorClient.java`            | New: `recordVisit()` → `POST /api/visits`, returns `OptionalLong`      |
| `backends/facade/src/main/java/org/fsweb/facade/client/VisitorProperties.java`        | New: `fsweb.visitor.*` (base URL, timeouts)                            |
| `backends/facade/src/main/java/org/fsweb/facade/client/VisitCount.java`               | New: response record `{"count": n}`                                    |
| `backends/facade/src/main/java/org/fsweb/facade/config/VisitorClientConfig.java`      | New: `visitorRestClient` bean (Boot builder + base URL + timeouts)     |
| `backends/facade/src/main/java/org/fsweb/facade/controller/HelloController.java`      | Calls `visitorClient.recordVisit()` on each request                    |
| `backends/facade/src/main/resources/application.yml`                                  | `fsweb.visitor` block                                                  |
| `backends/facade/src/main/resources/application-prod.yml`                             | `fsweb.visitor.base-url: ${VISITOR_URL:...}`                           |
| `backends/facade/src/test/java/org/fsweb/facade/controller/HelloControllerTests.java` | New: 4 tests with `MockRestServiceServer` as the visitor backend       |
| `README.md`, `CLAUDE.md`                                                              | Endpoint table, `VISITOR_URL`, structure tree, backend-to-backend note |
| `docs/008-hello-counts-visits.md`                                                     | This file                                                              |

No changes to the visitor backend or the frontend.

## Behavior to be aware of

- The page records one visit on load (`POST /api/visits` from `App.tsx`) and now one more per "Get Greeting" click. The count shown on the page is from page load; it does not refresh after a greeting.
- Anything that calls `/api/hello` (curl, health probes, tests against a running app) increments the counter.

## Verification

Run in a Linux container with OpenJDK 25.0.4.1 and the Maven Wrapper (Maven 3.9.16). Docker is not available there, so Docker Compose was disabled with `--spring.docker.compose.enabled=false`, OTLP export was turned off, and a local Redis on 6379 stood in for Valkey.

| Check                                                      | Result                                                                                         |
|------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| `./mvnw clean install`                                     | All 5 modules SUCCESS                                                                          |
| Tests                                                      | `FacadeApplicationTests` 1/1, `HelloControllerTests` 4/4, `VisitorApplicationTests` 1/1        |
| Both JARs running: `GET /api/visits` on 8081               | `{"count":0}`                                                                                  |
| `GET /api/hello?name=France`, `?name=A`, `?name=B` on 8080 | `Hello, France!`, `Hello, A!`, `Hello, B!`                                                     |
| `GET /api/visits` on 8081 after that                       | `{"count":3}`; Redis key `fs-web:visits` = 3                                                   |
| Facade log                                                 | `Visitor backend recorded visit, count is now 1` / `2` / `3` (DEBUG)                           |
| Visitor backend stopped, `GET /api/hello?name=down`        | `Hello, down!` HTTP 200 in 23 ms; `WARN ... Could not record visit ... Connection refused`     |
| Header received by a stub on 8081                          | `traceparent: 00-3c0de385…-…-03`; same trace ID in the facade log line, so the trace continues |

Still to check on a machine with Docker Desktop:

- `./mvnw spring-boot:run -pl backends/facade` and `-pl backends/visitor`, then click "Get Greeting" on `http://localhost:3000` and reload: the counter should go up by 2 per reload + click
- In Grafana (`http://localhost:3001`, Explore → Tempo), one trace for `GET /api/hello` should contain spans from `facade` and `visitor`
