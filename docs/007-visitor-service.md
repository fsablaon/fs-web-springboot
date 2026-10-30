# Visitor backend: the visit counter as its own service — 2026-10-30

Branch: `feature/visitor-service` (based on `origin/main` after the `demo` → `facade` rename, PR #23)

Goal: add a second Spring Boot backend, `visitor`, and move the visit counter (`/api/visits`) into it. `facade` keeps `/api/hello` and keeps serving the React app. This is the first backend added with the multi-module layout from `docs/004-multi-module-backends.md`.

## Modules after this change

| Module          | Path               | ArtifactId | Port | Owns                                           | Serves the UI |
|-----------------|--------------------|------------|------|------------------------------------------------|---------------|
| Facade backend  | `backends/facade`  | `facade`   | 8080 | `GET /api/hello`                               | Yes           |
| Visitor backend | `backends/visitor` | `visitor`  | 8081 | `GET /api/visits`, `POST /api/visits` (Valkey) | No            |

Reactor order: `fs-web-parent` → `fs-web-frontend` → `fs-web-backends` → `facade` → `visitor`.

## The visitor module

```
backends/visitor/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/org/fsweb/visitor/
    │   │   ├── VisitorApplication.java
    │   │   ├── config/OpenTelemetryLogbackConfig.java   # same as facade's
    │   │   ├── controller/VisitController.java          # moved from facade
    │   │   ├── dto/VisitCount.java                      # moved from facade
    │   │   └── service/VisitCounterService.java         # moved from facade
    │   └── resources/
    │       ├── application.yml                          # port 8081, Docker Compose file, name "visitor"
    │       ├── application-prod.yml                     # REDIS_HOST/REDIS_PORT, OTLP_ENDPOINT
    │       └── logback-spring.xml                       # console + OTEL appender
    └── test/java/org/fsweb/visitor/VisitorApplicationTests.java
```

| Item                      | Value                                                                                                                                                   |
|---------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| Parent                    | `org.fsweb:fs-web-backends` (Lombok, `spring-boot-starter-test`, compiler and Boot plugin settings inherited)                                           |
| Dependencies              | `spring-boot-starter-web`, `-actuator`, `-data-redis`, `-opentelemetry`, `opentelemetry-logback-appender-1.0`, `spring-boot-docker-compose` (optional) |
| Not a dependency          | `fs-web-frontend` — only `facade` serves the UI                                                                                                         |
| `spring.application.name` | `visitor` — also the OpenTelemetry `service.name`, so its traces and logs are separate from `facade` in Grafana                                         |
| `server.port`             | 8081 (`facade` is 8080)                                                                                                                                 |
| Docker Compose            | `spring.docker.compose.file: backends/compose.yaml`, `lifecycle-management: start-only`; shares the `fs-web-springboot` Compose project with `facade`   |
| Redis key                 | `fs-web:visits` — unchanged, so the count recorded before the move carries over                                                                        |
| Logging                   | `org.fsweb.visitor: DEBUG` in dev, `INFO` in the `prod` profile                                                                                         |

`backends/compose.yaml` also starts `kafka` (PR #22). The visitor backend has no Kafka client, so Spring Boot does not create a Kafka connection for it.

## Changes to facade

| File                                                                                          | Change                                                        |
|-----------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| `backends/facade/pom.xml`                                                                     | Removed `spring-boot-starter-data-redis`; description updated |
| `controller/VisitController.java`, `dto/VisitCount.java`, `service/VisitCounterService.java` | Deleted (now in `backends/visitor`)                           |
| `src/main/resources/application-prod.yml`                                                     | Removed the `spring.data.redis` block                         |
| `src/test/java/org/fsweb/facade/FacadeApplicationTests.java`                                  | Comment no longer mentions Redis                              |

`facade` now has no Redis client, so its `/actuator/health` no longer depends on Valkey.

## Routing /api/visits to the visitor backend

The React app calls `/api/visits` on its own origin. Who answers depends on how the app is served:

| Mode                           | Who serves the UI | How `/api/visits` reaches `visitor`                                                                                                                                    |
|--------------------------------|-------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Dev (`npm run dev`, port 3000) | Vite              | `frontend/vite.config.ts`: `'/api/visits'` → `http://localhost:8081`, listed before `'/api'` → `http://localhost:8080` (Vite uses the first matching prefix)           |
| Production JARs                | `facade` on 8080  | **Not handled in this change.** A reverse proxy (nginx, ingress, API gateway) in front of both JARs has to route `/api/visits` to 8081 and everything else to 8080   |

`frontend/src/App.tsx` text changes only: the unavailable message says "is the visitor backend running?" and the info line names the visitor backend and Valkey.

## Files changed

| File                          | Change                                                                    |
|-------------------------------|---------------------------------------------------------------------------|
| `backends/pom.xml`            | `<module>visitor</module>` added                                          |
| `backends/visitor/**`         | New module (see above)                                                    |
| `backends/facade/**`          | Visit counter and Redis removed (see above)                               |
| `frontend/vite.config.ts`     | Proxy entry for `/api/visits` → 8081                                      |
| `frontend/src/App.tsx`        | Two text strings                                                          |
| `README.md`                   | Three-terminal dev setup, module/endpoint tables, production run commands |
| `CLAUDE.md`                   | Module table, structure tree, commands, endpoint-ownership note           |
| `docs/007-visitor-service.md` | This file                                                                 |

## Verification

Run in a Linux container with OpenJDK 25.0.4.1 and the Maven Wrapper (Maven 3.9.16), on the branch rebased onto PR #23. Docker is not available there, so Docker Compose was disabled with `--spring.docker.compose.enabled=false`, OTLP export was turned off, and a local Redis 7 on 6379 stood in for Valkey.

| Check                                                                   | Result                                                                                        |
|-------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `./mvnw clean install`                                                  | `fs-web-springboot` → `fs-web-frontend` → `fs-web-backends` → `facade` → `visitor`, all SUCCESS |
| Tests                                                                   | `FacadeApplicationTests` 1/1, `VisitorApplicationTests` 1/1                                   |
| `visitor-0.0.1-SNAPSHOT.jar` contents                                   | No `fs-web-frontend`, Lombok or `spring-boot-docker-compose`                                  |
| `facade-0.0.1-SNAPSHOT.jar` contents                                    | `fs-web-frontend` jar present; no Redis/Lettuce, no `VisitController`                         |
| `java -jar visitor…jar`: `/actuator/health`                             | `UP` on 8081                                                                                  |
| `GET`, `POST`, `POST`, `GET /api/visits` on 8081                        | `{"count":0}`, `{"count":1}`, `{"count":2}`, `{"count":2}`; Redis key `fs-web:visits` = 2      |
| `java -jar facade…jar`: `/actuator/health`, `/api/hello?name=France`    | `UP` on 8080; `Hello, France!`                                                                |
| `GET /api/visits` on 8080 (facade)                                      | 404 — the endpoint is no longer in facade; SPA fallback does not apply to `api/` paths        |
| `GET /`, `GET /some/spa/route` on 8080                                  | 200 `text/html` (React app, SPA fallback)                                                     |
| Log service names                                                       | `[facade]` and `[visitor]`                                                                    |
| `./mvnw spring-boot:run -pl backends/visitor` from the project root     | Started and served `POST /api/visits` (checked before the rebase; the module is unchanged)    |
| `cd frontend && npm run lint`                                           | Passes                                                                                        |

Still to check on a machine with Docker Desktop:

- `./mvnw spring-boot:run -pl backends/visitor` with Docker running, to confirm it reuses the `fs-web-springboot` Compose containers started by `facade` (or starts them) and that `/actuator/health` on 8081 is `UP` against Valkey
- `npm run dev` with both backends running: the page's visit counter should increment through the Vite proxy
- In IntelliJ, reload the Maven project so `visitor` appears as a module
