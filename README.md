# fs-web-springboot

A full-stack web application with Spring Boot backend and React frontend.

## Technologies

**Backend:**
- Java 25
- Spring Boot 4.1.2
- Spring Framework 7.0
- Spring Web
- Spring Data Redis
- Spring Boot Actuator + OpenTelemetry (OTLP)
- Spring Boot Docker Compose support
- Lombok
- Maven (multi-module: one module per backend plus a frontend module)

**Frontend:**
- React 19
- TypeScript
- Vite
- CSS Modules

## Prerequisites

- JDK 25 or later
- No Maven install needed: the Maven Wrapper (`./mvnw`, `mvnw.cmd` on Windows) downloads Maven 3.9.16 on first run
- Node.js 26+ and npm 12+ (for frontend development)
- Docker Desktop (or another Docker engine with `docker compose`), running before you start the backend

## Getting Started

Backend commands use the Maven Wrapper. On Windows, run `mvnw.cmd` (cmd) or `.\mvnw.cmd` (PowerShell) in place of `./mvnw`.

### Development Mode (Recommended)

For development with hot reload:

1. **Start the facade backend** (Terminal 1):
```bash
./mvnw install -DskipTests                # first time only: installs the parent POM and builds the frontend jar
./mvnw spring-boot:run -pl backends/facade
```
The facade backend (`/api/hello`, serves the UI in production) runs on `http://localhost:8080`

2. **Start the visitor backend** (Terminal 2):
```bash
./mvnw spring-boot:run -pl backends/visitor
```
The visitor backend (`/api/visits`) runs on `http://localhost:8081`

3. **Start the frontend** (Terminal 3):
```bash
cd frontend
npm install  # Only needed first time
npm run dev
```
The frontend will run on `http://localhost:3000`

4. **Access the application** at `http://localhost:3000`

The Vite dev server proxies `/api/visits` to the visitor backend (8081) and every other `/api/*` request to the facade backend (8080), giving you hot reload for both frontend and backend changes.

### Local Services (Docker Compose)

`backends/compose.yaml` defines the services used in development, shared by all backends:

| Service  | Image                        | Purpose                                                                        |
|----------|------------------------------|--------------------------------------------------------------------------------|
| `valkey` | `valkey/valkey:9.1.3-alpine` | Visit counter storage (Redis-compatible; the `redis` service is commented out) |
| `lgtm`   | `grafana/otel-lgtm:0.32.1`   | Grafana, Loki (logs), Tempo (traces), Mimir (metrics), OTel Collector          |

`./mvnw spring-boot:run -pl backends/facade` (or `-pl backends/visitor`) starts these containers automatically (via `spring-boot-docker-compose`) and connects the app to them, so no hosts, ports or OTLP endpoints are configured for development. Both backends share the same containers; the first one started creates them. The containers keep running after the app stops (`lifecycle-management: start-only`).

- **Grafana:** `http://localhost:3001` (Explore → Tempo for traces, Loki for logs, Prometheus for metrics; service names `facade` and `visitor`)
- **Stop the containers:** `docker compose -p fs-web-springboot down`
- **Actuator:** `http://localhost:8080/actuator/health` (facade), `http://localhost:8081/actuator/health` (visitor)

See [docs/001-docker-compose-observability.md](docs/001-docker-compose-observability.md) for the full design.

### Production Build

Build everything, then run one JAR per backend:

```bash
./mvnw clean install
java -jar backends/facade/target/facade-0.0.1-SNAPSHOT.jar      # UI + /api/hello on 8080
java -jar backends/visitor/target/visitor-0.0.1-SNAPSHOT.jar    # /api/visits on 8081
```

Access the application at `http://localhost:8080`. The UI calls `/api/visits` on its own origin, so outside the Vite dev server a reverse proxy (nginx, an ingress, an API gateway) has to route `/api/visits` to the visitor backend and everything else to the facade backend.

The packaged JARs do not include Docker Compose support. Run them with the `prod` profile and set the OTLP endpoint (and Redis for the visitor backend):

```bash
OTLP_ENDPOINT=http://collector:4318 \
  java -jar backends/facade/target/facade-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
REDIS_HOST=valkey.example OTLP_ENDPOINT=http://collector:4318 \
  java -jar backends/visitor/target/visitor-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

The Maven build automatically:
1. Installs Node.js and npm (via frontend-maven-plugin, in the `frontend` module)
2. Installs frontend dependencies
3. Builds the React app and packages it as the `fs-web-frontend` jar
4. Builds each backend (`facade`, `visitor`); the facade backend JAR includes the frontend jar

## Project Structure

```
fs-web-springboot/
├── pom.xml                # Parent + aggregator POM (shared versions and plugin settings)
├── backends/
│   ├── pom.xml            # Parent POM for all backends (shared backend build settings)
│   ├── compose.yaml       # Valkey + Grafana LGTM for local development (shared by all backends)
│   ├── facade/            # Facade backend module (UI + /api/hello, port 8080)
│   │   ├── pom.xml
│   │   ├── src/main/java/       # Spring Boot backend code
│   │   └── src/main/resources/  # Backend configuration
│   └── visitor/           # Visitor backend module (/api/visits, port 8081)
│       ├── pom.xml
│       ├── src/main/java/       # Visit counter (Valkey/Redis)
│       └── src/main/resources/  # Backend configuration
├── frontend/              # React frontend module
│   ├── pom.xml            # Builds the React app into a jar of static resources
│   ├── src/               # React components and TypeScript
│   └── vite.config.ts     # Vite configuration with proxy
└── docs/                  # Design docs
```

### Maven modules

| Module          | Path               | ArtifactId        | Port | Output                                                        |
|-----------------|--------------------|-------------------|------|---------------------------------------------------------------|
| Parent          | `.`                | `fs-web-parent`   |      | POM only                                                      |
| Frontend        | `frontend/`        | `fs-web-frontend` |      | Jar with the built React app in `static/`                     |
| Backends parent | `backends/`        | `fs-web-backends` |      | POM only                                                      |
| Facade backend  | `backends/facade`  | `facade`          | 8080 | Executable Spring Boot JAR: `/api/hello`, serves the frontend |
| Visitor backend | `backends/visitor` | `visitor`         | 8081 | Executable Spring Boot JAR: `/api/visits` (Valkey counter)    |

To add another backend, see [docs/004-multi-module-backends.md](docs/004-multi-module-backends.md); the visitor backend is described in [docs/007-visitor-service.md](docs/007-visitor-service.md).

## API Endpoints

| Endpoint                       | Backend   | Description                                                  |
|--------------------------------|-----------|--------------------------------------------------------------|
| `GET /api/hello?name=YourName` | `facade`  | Returns a greeting message                                   |
| `GET /api/visits`              | `visitor` | Returns the current visit count from Valkey (`{"count": n}`) |
| `POST /api/visits`             | `visitor` | Increments the visit count and returns the new value         |

## Running Tests

### Backend Tests
```bash
./mvnw test                                                    # all modules
./mvnw test -pl backends/facade -Dtest=FacadeApplicationTests     # one test class in one backend
```

### Frontend Tests
```bash
cd frontend
npm run lint
```

## Additional Commands

### Backend Only
```bash
./mvnw spring-boot:run -pl backends/facade    # Run the facade backend (8080)
./mvnw spring-boot:run -pl backends/visitor   # Run the visitor backend (8081)
./mvnw install -pl backends/facade -am        # Build one backend and the modules it depends on
./mvnw clean                                  # Clean build artifacts (all modules)
./mvnw package                                # Package all modules
```

### Frontend Only
```bash
cd frontend
npm run dev             # Start dev server
npm run build           # Build for production
npm run preview         # Preview production build
npm run lint            # Run ESLint
```
