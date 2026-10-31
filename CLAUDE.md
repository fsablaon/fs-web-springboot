# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a full-stack web application with Spring Boot backend and React frontend.

**Maven modules** (multi-module build, root `pom.xml` is the parent and aggregator, `org.fsweb:fs-web-parent`, packaging `pom`):

| Module          | Path               | ArtifactId        | Purpose                                                                                                                                                      |
|-----------------|--------------------|-------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Frontend        | `frontend/`        | `fs-web-frontend` | Builds the React app; jar holds `static/**`                                                                                                                  |
| Backends parent | `backends/`        | `fs-web-backends` | Parent + aggregator for all backends (packaging `pom`)                                                                                                       |
| Facade backend  | `backends/facade`  | `facade`          | Spring Boot app (port 8080): `/api/hello`, `/api/overview`; serves the frontend jar; publishes visit events to Kafka; calls `visitor` and `stocks` over HTTP |
| Visitor backend | `backends/visitor` | `visitor`         | Spring Boot app (port 8081): `/api/visits` visit counter in Valkey; consumes visit events from Kafka                                                         |
| Stocks backend  | `backends/stocks`  | `stocks`          | Spring Boot app (port 8082): `/api/stocks` in-memory stock list                                                                                              |

- Root `pom.xml`: settings for every module (Java version, encoding, Node/npm and `frontend-maven-plugin` versions)
- `backends/pom.xml`: backend-only settings (Lombok annotation processor, `spring-boot-maven-plugin` config, OpenTelemetry appender version, `fs-web-frontend` version) and shared backend dependencies (Lombok, `spring-boot-starter-test`)
- New backends go in `backends/<name>/`, use `fs-web-backends` as parent and are listed in `backends/pom.xml` `<modules>`; steps in `docs/004-multi-module-backends.md`

**Backends:** `backends/facade` (base package `org.fsweb.facade`, main class `FacadeApplication`, port 8080), `backends/visitor` (base package `org.facade.visitor`, main class `VisitorApplication`, port 8081, owns the visit counter; design in `docs/007-visitor-service.md`) and `backends/stocks` (base package `org.fsweb.stocks`, main class `StocksApplication`, port 8082, owns `/api/stocks`, in-memory for now; design in `docs/009-stocks-service.md`). Each `/api/hello` call in facade publishes a `VisitEvent` (JSON) to the Kafka topic `fs-web.visits` through `VisitEventPublisher` (`KafkaTemplate`); the visitor backend's `VisitEventListener` (`@KafkaListener`, group `visitor`) consumes it and increments the counter. The topic is created by the visitor's `KafkaTopicConfig` (`NewTopic` bean → Boot's `KafkaAdmin` at startup, idempotent); the dev broker's auto-create is the fallback if the facade publishes first. Design in `docs/011-kafka-visit-events.md` (replaced the REST call of `docs/008-hello-counts-visits.md`). `GET /api/overview` in facade reads the visit count (`GET /api/visits`) and the stock list (`GET /api/stocks`) over HTTP; the facade's HTTP clients are Spring Boot HTTP service client groups: one `@HttpExchange` interface per downstream in `client/`, registered with `@ImportHttpServices(group = ...)` in `config/HttpServiceClientsConfig.java`, settings in `spring.http.serviceclient.<group>.*` (defaults in `spring.http.clients.*`), failure policy in the `*Client` wrapper; design in `docs/013-facade-http-clients.md`. Shared settings:
- **Base Package**: `org.fsweb.<backend>`
- **Java Version**: 25. The root `pom.xml` runs `maven-toolchains-plugin:select-jdk-toolchain` (`toolchain.jdk.version` `[25,26)`), so compile, tests and `spring-boot:run` use a JDK 25 found on the machine even when Maven runs on another JDK (17+); the build stops at `validate` if none is found. Details in `docs/012-jdk25-toolchain.md`
- **Spring Boot Version**: 4.1.2
- **Spring Framework Version**: 7.0
- **Configuration**: Uses `application.yml` for configuration
- **Build Tool**: Maven via Maven Wrapper 3.3.4 (`./mvnw`, `mvnw.cmd` on Windows), pinned to Maven 3.9.16 in `.mvn/wrapper/maven-wrapper.properties`; use the wrapper instead of a global `mvn`

**Frontend:**
- **Framework**: React 19 with TypeScript
- **Build Tool**: Vite
- **Styling**: CSS Modules
- **Dev Server Port**: 3000
- **Location**: `frontend/` directory

**Local services (Docker Compose):**
- `backends/compose.yaml`, shared by all backends (Compose project name `fs-web-springboot`): `valkey` (Valkey 9, Redis-compatible; the `redis` service is commented out), `lgtm` (Grafana OTel LGTM stack), `kafka` (KRaft, fixed port 9092 for the host, `kafka:19092` inside the Compose network), `kafka-ui` (Kafbat UI on `http://localhost:8090`, not a Boot service connection), `mysql` (MySQL 9.7 LTS, db/user/password `fsweb`), `postgres` (PostgreSQL 18, db/user/password `fsweb`) and `dynamodb` (DynamoDB Local, fixed port 8000, `-sharedDb -inMemory`)
- `mysql` and `postgres` both produce `JdbcConnectionDetails`; a backend with a DataSource must label the one it does not use with `org.springframework.boot.ignore: true` (or Boot fails with two candidates). DynamoDB Local has no Boot service connection: configure the endpoint `http://localhost:8000` explicitly. Details in `docs/008-mysql-postgres-dynamodb-compose.md`
- Backends set `spring.docker.compose.file: backends/compose.yaml`; the path is relative to the project root, which is the working directory for `spring-boot:run` (set in `backends/pom.xml`) and IntelliJ's default
- `spring-boot-docker-compose` (optional dependency) starts them on `./mvnw spring-boot:run -pl backends/<name>` (all backends share the containers) and creates the Redis, Kafka (`spring.kafka.bootstrap-servers`) and OTLP connection details automatically (`valkey` has the label `org.springframework.boot.service-connection: redis` because Boot only matches `redis` image names); do not hardcode hosts/ports for dev
- Grafana UI: `http://localhost:3001` (host port 3000 is the Vite dev server). Project dashboards live in `backends/grafana/dashboards/*.json` and are bind-mounted into the `lgtm` container (provider `backends/grafana/dashboards-provisioning.yaml`, folder `fs-web`); add a dashboard by dropping its JSON there. Kafka UI: `http://localhost:8090`. Details in `docs/014-kafka-ui-and-dashboard.md`
- Containers keep running after the app stops; `docker compose -p fs-web-springboot down` stops them
- Docker Compose support is skipped in tests and excluded from the packaged JAR; production settings live in `application-prod.yml`
- Design notes: `docs/001-docker-compose-observability.md`

## Build & Development Commands

### Full-Stack Development (Recommended)
For development with hot reload:
1. **First time only**: `./mvnw install -DskipTests` (installs the parent POM and builds the `fs-web-frontend` jar the backend depends on)
2. **Start facade backend**: `./mvnw spring-boot:run -pl backends/facade` (runs on port 8080)
3. **Start visitor backend** (separate terminal): `./mvnw spring-boot:run -pl backends/visitor` (runs on port 8081)
4. **Start stocks backend** (separate terminal): `./mvnw spring-boot:run -pl backends/stocks` (runs on port 8082)
5. **Start frontend** (separate terminal): `cd frontend && npm run dev` (runs on port 3000)
6. Access the app at `http://localhost:3000` (Vite proxies `/api/visits` to 8081, `/api/stocks` to 8082 and other `/api/*` calls to 8080)

Steps 2–5 in one command: `scripts/dev.sh` (Linux/macOS/WSL) or `scripts\dev.cmd` (Windows). Both stop every server when the script is stopped (Ctrl+C, killed, window closed); `dev.sh` also stops the rest when one server exits. `--no-frontend` / `-NoFrontend` skips Vite. Design in `docs/010-dev-start-scripts.md`.

### Production Build
- **Full build**: `./mvnw clean install` (builds the frontend module, then each backend; the facade backend JAR includes the frontend jar)
- **Run production build**: `java -jar backends/facade/target/facade-0.0.1-SNAPSHOT.jar`, `java -jar backends/visitor/target/visitor-0.0.1-SNAPSHOT.jar` and `java -jar backends/stocks/target/stocks-0.0.1-SNAPSHOT.jar`
- Access at `http://localhost:8080`; outside the Vite dev server a reverse proxy must route `/api/visits` to 8081 and `/api/stocks` to 8082

### Backend Only (Maven Wrapper)
Run from the project root. `-pl backends/<name>` selects one module; add `-am` to also build the modules it depends on.
- **Build all modules**: `./mvnw clean install`
- **Build one backend**: `./mvnw clean install -pl backends/facade -am`
- **Run application**: `./mvnw spring-boot:run -pl backends/facade`, `./mvnw spring-boot:run -pl backends/visitor` or `./mvnw spring-boot:run -pl backends/stocks`
- **Run tests (all modules)**: `./mvnw test`
- **Run single test**: `./mvnw test -pl backends/facade -Dtest=ClassName#methodName`
- **Package**: `./mvnw package`
- **Clean**: `./mvnw clean`

### Frontend Only (npm)
All commands run from `frontend/` directory:
- **Install dependencies**: `npm install`
- **Start dev server**: `npm run dev`
- **Build for production**: `npm run build` (outputs to `frontend/target/classes/static/`, packaged by the `fs-web-frontend` module)
- **Lint**: `npm run lint`
- **Preview production build**: `npm run preview`

## Project Structure

```
fs-web-springboot/
├── pom.xml                              # Parent + aggregator POM (packaging pom)
├── backends/
│   ├── pom.xml                          # Backend parent + aggregator (fs-web-backends)
│   ├── compose.yaml                     # Valkey, LGTM, Kafka, Kafka UI, MySQL, PostgreSQL, DynamoDB Local (dev), shared by all backends
│   ├── grafana/                         # Grafana provisioning mounted into lgtm: dashboards-provisioning.yaml + dashboards/*.json
│   ├── facade/                          # Facade backend module (artifactId facade, port 8080)
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/
│   │       │   ├── java/org/fsweb/facade/
│   │       │   │   ├── FacadeApplication.java   # Main Spring Boot application
│   │       │   │   ├── client/                  # VisitorApi, StocksApi (@HttpExchange), VisitorClient, StocksClient (failure policy), VisitCount, Stock
│   │       │   │   ├── config/                  # Configuration classes
│   │       │   │   │   ├── WebConfig.java       # SPA routing configuration
│   │       │   │   │   ├── HttpServiceClientsConfig.java  # @ImportHttpServices groups: visitor, stocks
│   │       │   │   │   └── OpenTelemetryLogbackConfig.java  # Attaches Logback OTEL appender to the SDK
│   │       │   │   ├── controller/              # REST API controllers
│   │       │   │   │   ├── HelloController.java
│   │       │   │   │   └── OverviewController.java  # /api/overview: visit count + stock list via the HTTP clients
│   │       │   │   ├── dto/                     # Overview
│   │       │   │   └── event/                   # VisitEvent, VisitEventProperties, VisitEventPublisher (KafkaTemplate -> fs-web.visits)
│   │       │   └── resources/
│   │       │       ├── application.yml          # Backend configuration (Kafka producer, facade.visits.topic, spring.http.serviceclient.*)
│   │       │       ├── application-prod.yml     # Production Kafka brokers, VISITOR_URL/STOCKS_URL and OTLP settings (env vars)
│   │       │       └── logback-spring.xml       # Console + OpenTelemetry log appenders
│   │       └── test/
│   │           └── java/org/fsweb/facade/
│   │               ├── FacadeApplicationTests.java
│   │               ├── config/HttpServiceClientsConfigTests.java  # visitor and stocks groups bind from application.yml
│   │               ├── controller/HelloControllerTests.java  # Mock KafkaTemplate: one event per greeting, Kafka failures swallowed
│   │               ├── controller/OverviewControllerTests.java  # Two MockRestServiceServers; each downstream may be down
│   │               └── event/VisitEventPublisherTests.java   # @EmbeddedKafka: JSON wire format, key, no type header
│   ├── visitor/                         # Visitor backend module (artifactId visitor, port 8081)
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/
│   │       │   ├── java/org/fsweb/visitor/
│   │       │   │   ├── VisitorApplication.java  # Main Spring Boot application
│   │       │   │   ├── config/
│   │       │   │   │   ├── KafkaTopicConfig.java  # NewTopic fs-web.visits (created at startup if missing)
│   │       │   │   │   └── OpenTelemetryLogbackConfig.java
│   │       │   │   ├── controller/
│   │       │   │   │   └── VisitController.java # /api/visits (Valkey counter)
│   │       │   │   ├── dto/                     # API records (VisitCount)
│   │       │   │   ├── event/                   # VisitEvent, VisitEventProperties, VisitEventListener (@KafkaListener -> increment)
│   │       │   │   └── service/                 # VisitCounterService (StringRedisTemplate)
│   │       │   └── resources/
│   │       │       ├── application.yml          # Port 8081, Docker Compose file, Kafka consumer (JSON, ErrorHandlingDeserializer)
│   │       │       ├── application-prod.yml     # Production Redis/Kafka/OTLP settings (env vars)
│   │       │       └── logback-spring.xml
│   │       └── test/
│   │           └── java/org/fsweb/visitor/
│   │               ├── VisitorApplicationTests.java
│   │               └── event/VisitEventListenerTests.java   # @EmbeddedKafka: counts facade-style JSON, skips bad records
│   └── stocks/                          # Stocks backend module (artifactId stocks, port 8082)
│       ├── pom.xml
│       └── src/
│           ├── main/
│           │   ├── java/org/fsweb/stocks/
│           │   │   ├── StocksApplication.java   # Main Spring Boot application
│           │   │   ├── config/
│           │   │   │   └── OpenTelemetryLogbackConfig.java
│           │   │   ├── controller/
│           │   │   │   └── StockController.java # /api/stocks, /api/stocks/{symbol}
│           │   │   ├── dto/                     # API records (Stock)
│           │   │   ├── exception/               # StockNotFoundException, GlobalExceptionHandler (404 ProblemDetail)
│           │   │   └── service/                 # StockService (in-memory map; swap for a real data source)
│           │   └── resources/
│           │       ├── application.yml          # Port 8082, Docker Compose file
│           │       ├── application-prod.yml     # Production OTLP settings (env vars)
│           │       └── logback-spring.xml
│           └── test/
│               └── java/org/fsweb/stocks/
│                   ├── StocksApplicationTests.java
│                   ├── controller/StockControllerTest.java   # HTTP tests on a random port
│                   └── service/StockServiceTest.java
├── frontend/                            # Frontend module (artifactId fs-web-frontend)
│   ├── pom.xml                          # Runs npm install / npm run build via frontend-maven-plugin
│   ├── src/
│   │   ├── App.tsx                      # Main React component
│   │   ├── App.module.css               # Component styles
│   │   ├── main.tsx                     # React entry point
│   │   └── index.css                    # Global styles
│   ├── vite.config.ts                   # Vite configuration (proxy setup)
│   ├── tsconfig.json                    # TypeScript configuration
│   ├── package.json                     # Frontend dependencies
│   └── target/classes/static/           # Built React app (generated)
├── scripts/                             # dev.sh (bash) and dev.cmd + dev.ps1 (Windows): start/stop all dev servers together
├── docs/                                # Design docs
├── .mvn/wrapper/                        # maven-wrapper.properties (Maven version)
└── mvnw / mvnw.cmd                      # Maven Wrapper scripts (Unix / Windows)
```

## Architecture Guidelines

### Typical Spring Boot Layered Architecture
- **Controllers** (`@RestController`/`@Controller`): Handle HTTP requests, map to service methods
- **Services** (`@Service`): Business logic layer, orchestrates operations
- **Repositories** (`@Repository`): Data access layer, interfaces extending JpaRepository/CrudRepository
- **Models/Entities** (`@Entity`): JPA entities representing database tables
- **DTOs**: Data transfer objects for API requests/responses
- **Configuration** (`@Configuration`): Spring configuration classes

### Spring Boot Conventions
- Application entry point: one `@SpringBootApplication` class with a `main()` method per backend (`FacadeApplication`, `VisitorApplication`)
- Configuration: `application.yml` in each backend's `src/main/resources/`
- Use dependency injection via constructor injection (preferred) or `@Autowired`
- Exception handling: Use `@ControllerAdvice` for global exception handling
- Lombok is enabled: Use annotations like `@Slf4j`, `@Data`, `@Builder` to reduce boilerplate

## Frontend-Backend Integration

### Development Mode
- Frontend runs on Vite dev server (port 3000) with hot reload
- Backend runs on Spring Boot (port 8080)
- Vite proxy configuration in `frontend/vite.config.ts` forwards `/api/*` requests to backend
- CORS is handled by the proxy, no additional backend configuration needed

### Production Mode
- The `frontend` module builds the React app into `frontend/target/classes/static/` and packages it as the `fs-web-frontend` jar
- A backend that serves the UI depends on `fs-web-frontend` (runtime scope); Spring Boot serves it from `classpath:/static/`
- `WebConfig.java` handles SPA routing: non-API routes fall back to `index.html`
- Each backend is its own executable JAR; the facade backend JAR contains the frontend

### API Conventions
- All backend API endpoints are prefixed with `/api`
- Frontend makes relative fetch calls (e.g., `fetch('/api/hello')`)
- This works in both dev (proxied) and production (same origin)
- Endpoint ownership: `/api/visits` → `visitor` (8081); `/api/stocks` → `stocks` (8082); everything else → `facade` (8080). `/api/hello` does not call the visitor backend over HTTP: it publishes visit events to the Kafka topic `fs-web.visits` (`facade.visits.topic`; brokers from Docker Compose in dev, `KAFKA_BOOTSTRAP_SERVERS` in the `prod` profile) and the visitor backend consumes them (`visitor.visits.topic`, group `visitor.visits.group-id`). Both backends have their own `VisitEvent` record with the same JSON fields; records carry no Java type header. `/api/overview` does call `visitor` and `stocks` over HTTP, at `spring.http.serviceclient.{visitor,stocks}.base-url` (`VISITOR_URL` / `STOCKS_URL` in the `prod` profile), not through the reverse proxy. In dev, `frontend/vite.config.ts` has one proxy entry per owner (longest prefix first). In production the facade JAR serves the UI, so a reverse proxy in front of the JARs must route `/api/visits` to the visitor backend and `/api/stocks` to the stocks backend.


## Custom Conventions
- Always create a branch for new implementation work
- Always summary the task and place summary in docs directory, named `docs/NNN-<topic>.md` where `NNN` is the next three-digit number (e.g. `005-<topic>.md`), so the files sort in the order the changes were made
- Format markdown tables in human readable form
- Keep separate features/tasks in separate worktrees (`.worktrees/<name>` on branch `feature/<name>`); use the main branch unless specified

# Verify changes
- Always run a `mvn clean install` or `./mvnw.cmd clean install` (windows)
- In the Claude desktop app's linked Linux VM (no JDK 25 there): prefix Maven commands with `. .vm/env.sh &&` (JDK 25, proxy and Maven cache in the gitignored `.vm/`, see `.vm/README.md`). VM commands are killed after about 3 minutes, so build one module at a time with `-pl` if needed
- Run the backend app `./mvnw spring-boot:run`