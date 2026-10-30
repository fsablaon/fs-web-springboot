# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a full-stack web application with Spring Boot backend and React frontend.

**Maven modules** (multi-module build, root `pom.xml` is the parent and aggregator, `org.fsweb:fs-web-parent`, packaging `pom`):

| Module          | Path              | ArtifactId        | Purpose                                                            |
|-----------------|-------------------|-------------------|--------------------------------------------------------------------|
| Frontend        | `frontend/`       | `fs-web-frontend` | Builds the React app; jar holds `static/**`                        |
| Backends parent | `backends/`       | `fs-web-backends` | Parent + aggregator for all backends (packaging `pom`)             |
| Facade backend  | `backends/facade` | `facade`          | Spring Boot app; serves the API and the frontend jar               |

- Root `pom.xml`: settings for every module (Java version, encoding, Node/npm and `frontend-maven-plugin` versions)
- `backends/pom.xml`: backend-only settings (Lombok annotation processor, `spring-boot-maven-plugin` config, OpenTelemetry appender version, `fs-web-frontend` version) and shared backend dependencies (Lombok, `spring-boot-starter-test`)
- New backends go in `backends/<name>/`, use `fs-web-backends` as parent and are listed in `backends/pom.xml` `<modules>`; steps in `docs/004-multi-module-backends.md`

**Backend (`backends/facade`):**
- **Base Package**: `org.fsweb.facade`
- **Java Version**: 25
- **Spring Boot Version**: 4.1.2
- **Spring Framework Version**: 7.0
- **Main Application Class**: `FsWebApplication`
- **Configuration**: Uses `application.yml` for configuration
- **Build Tool**: Maven via Maven Wrapper 3.3.4 (`./mvnw`, `mvnw.cmd` on Windows), pinned to Maven 3.9.16 in `.mvn/wrapper/maven-wrapper.properties`; use the wrapper instead of a global `mvn`

**Frontend:**
- **Framework**: React 19 with TypeScript
- **Build Tool**: Vite
- **Styling**: CSS Modules
- **Dev Server Port**: 3000
- **Location**: `frontend/` directory

**Local services (Docker Compose):**
- `backends/compose.yaml`, shared by all backends (Compose project name `fs-web-springboot`): `valkey` (Valkey 9, Redis-compatible; the `redis` service is commented out) and `lgtm` (Grafana OTel LGTM stack)
- Backends set `spring.docker.compose.file: backends/compose.yaml`; the path is relative to the project root, which is the working directory for `spring-boot:run` (set in `backends/pom.xml`) and IntelliJ's default
- `spring-boot-docker-compose` (optional dependency) starts them on `./mvnw spring-boot:run -pl backends/facade` and creates the Redis and OTLP connection details automatically (`valkey` has the label `org.springframework.boot.service-connection: redis` because Boot only matches `redis` image names); do not hardcode hosts/ports for dev
- Grafana UI: `http://localhost:3001` (host port 3000 is the Vite dev server)
- Containers keep running after the app stops; `docker compose -p fs-web-springboot down` stops them
- Docker Compose support is skipped in tests and excluded from the packaged JAR; production settings live in `application-prod.yml`
- Design notes: `docs/001-docker-compose-observability.md`

## Build & Development Commands

### Full-Stack Development (Recommended)
For development with hot reload:
1. **First time only**: `./mvnw install -DskipTests` (installs the parent POM and builds the `fs-web-frontend` jar the backend depends on)
2. **Start backend**: `./mvnw spring-boot:run -pl backends/facade` (runs on port 8080)
3. **Start frontend** (in separate terminal): `cd frontend && npm run dev` (runs on port 3000)
4. Access the app at `http://localhost:3000` (Vite proxies API calls to port 8080)

### Production Build
- **Full build**: `./mvnw clean install` (builds the frontend module, then each backend; the facade backend JAR includes the frontend jar)
- **Run production build**: `java -jar backends/facade/target/facade-0.0.1-SNAPSHOT.jar`
- Access at `http://localhost:8080`

### Backend Only (Maven Wrapper)
Run from the project root. `-pl backends/<name>` selects one module; add `-am` to also build the modules it depends on.
- **Build all modules**: `./mvnw clean install`
- **Build one backend**: `./mvnw clean install -pl backends/facade -am`
- **Run application**: `./mvnw spring-boot:run -pl backends/facade`
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
│   ├── compose.yaml                     # Valkey + Grafana LGTM (dev), shared by all backends
│   └── facade/                          # Facade backend module (artifactId facade)
│       ├── pom.xml
│       └── src/
│           ├── main/
│           │   ├── java/org/fsweb/facade/
│           │   │   ├── FsWebApplication.java    # Main Spring Boot application
│           │   │   ├── config/                  # Configuration classes
│           │   │   │   ├── WebConfig.java       # SPA routing configuration
│           │   │   │   └── OpenTelemetryLogbackConfig.java  # Attaches Logback OTEL appender to the SDK
│           │   │   ├── controller/              # REST API controllers
│           │   │   │   ├── HelloController.java
│           │   │   │   └── VisitController.java # /api/visits (Redis counter)
│           │   │   ├── dto/                     # API records (VisitCount)
│           │   │   └── service/                 # Business logic (VisitCounterService)
│           │   └── resources/
│           │       ├── application.yml          # Backend configuration
│           │       ├── application-prod.yml     # Production Redis/OTLP settings (env vars)
│           │       └── logback-spring.xml       # Console + OpenTelemetry log appenders
│           └── test/
│               └── java/org/fsweb/facade/
│                   └── FsWebApplicationTests.java
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
- Application entry point: `FsWebApplication` class with `@SpringBootApplication` and `main()` method
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


## Custom Conventions
- Always create a branch for new implementation work
- Always summary the task and place summary in docs directory, named `docs/NNN-<topic>.md` where `NNN` is the next three-digit number (e.g. `005-<topic>.md`), so the files sort in the order the changes were made
- Format markdown tables in human readable form

# Verify changes
- Always run a `mvn clean install` or `./mvnw.cmd clean install` (windows)
- Run the backend app `./mvnw spring-boot:run`