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

1. **Start the backend** (Terminal 1):
```bash
./mvnw install -DskipTests                # first time only: installs the parent POM and builds the frontend jar
./mvnw spring-boot:run -pl backends/facade
```
The backend will run on `http://localhost:8080`

2. **Start the frontend** (Terminal 2):
```bash
cd frontend
npm install  # Only needed first time
npm run dev
```
The frontend will run on `http://localhost:3000`

3. **Access the application** at `http://localhost:3000`

The Vite dev server will proxy API requests to the Spring Boot backend, giving you hot reload for both frontend and backend changes.

### Local Services (Docker Compose)

`backends/compose.yaml` defines the services used in development, shared by all backends:

| Service  | Image                        | Purpose                                                                        |
|----------|------------------------------|--------------------------------------------------------------------------------|
| `valkey` | `valkey/valkey:9.1.3-alpine` | Visit counter storage (Redis-compatible; the `redis` service is commented out) |
| `lgtm`   | `grafana/otel-lgtm:0.32.1`   | Grafana, Loki (logs), Tempo (traces), Mimir (metrics), OTel Collector          |

`./mvnw spring-boot:run -pl backends/facade` starts these containers automatically (via `spring-boot-docker-compose`) and connects the app to them, so no hosts, ports or OTLP endpoints are configured for development. The containers keep running after the app stops (`lifecycle-management: start-only`).

- **Grafana:** `http://localhost:3001` (Explore → Tempo for traces, Loki for logs, Prometheus for metrics)
- **Stop the containers:** `docker compose -p fs-web-springboot down`
- **Actuator:** `http://localhost:8080/actuator/health`

See [docs/001-docker-compose-observability.md](docs/001-docker-compose-observability.md) for the full design.

### Production Build

Build and run the complete application as a single JAR:

```bash
./mvnw clean install
java -jar backends/facade/target/facade-0.0.1-SNAPSHOT.jar
```

Access the application at `http://localhost:8080`

The packaged JAR does not include Docker Compose support. Run it with the `prod` profile and set the Redis and OTLP endpoints:

```bash
REDIS_HOST=redis.example OTLP_ENDPOINT=http://collector:4318 \
  java -jar backends/facade/target/facade-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

The Maven build automatically:
1. Installs Node.js and npm (via frontend-maven-plugin, in the `frontend` module)
2. Installs frontend dependencies
3. Builds the React app and packages it as the `fs-web-frontend` jar
4. Builds each backend; the facade backend JAR includes the frontend jar

## Project Structure

```
fs-web-springboot/
├── pom.xml                # Parent + aggregator POM (shared versions and plugin settings)
├── backends/
│   ├── pom.xml            # Parent POM for all backends (shared backend build settings)
│   ├── compose.yaml       # Valkey + Grafana LGTM for local development (shared by all backends)
│   └── facade/            # Facade backend module
│       ├── pom.xml
│       ├── src/main/java/       # Spring Boot backend code
│       └── src/main/resources/  # Backend configuration
├── frontend/              # React frontend module
│   ├── pom.xml            # Builds the React app into a jar of static resources
│   ├── src/               # React components and TypeScript
│   └── vite.config.ts     # Vite configuration with proxy
└── docs/                  # Design docs
```

### Maven modules

| Module          | Path              | ArtifactId        | Output                                       |
|-----------------|-------------------|-------------------|----------------------------------------------|
| Parent          | `.`               | `fs-web-parent`   | POM only                                     |
| Frontend        | `frontend/`       | `fs-web-frontend` | Jar with the built React app in `static/`    |
| Backends parent | `backends/`       | `fs-web-backends` | POM only                                     |
| Facade backend  | `backends/facade` | `facade`          | Executable Spring Boot JAR                   |

To add another backend, see [docs/004-multi-module-backends.md](docs/004-multi-module-backends.md).

## API Endpoints

- `GET /api/hello?name=YourName` - Returns a greeting message
- `GET /api/visits` - Returns the current visit count from Redis (`{"count": n}`)
- `POST /api/visits` - Increments the visit count and returns the new value

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
./mvnw spring-boot:run -pl backends/facade     # Run the facade backend
./mvnw install -pl backends/facade -am         # Build one backend and the modules it depends on
./mvnw clean                                 # Clean build artifacts (all modules)
./mvnw package                               # Package all modules
```

### Frontend Only
```bash
cd frontend
npm run dev             # Start dev server
npm run build           # Build for production
npm run preview         # Preview production build
npm run lint            # Run ESLint
```
