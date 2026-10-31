# fs-web-springboot

A full-stack web application with Spring Boot backend and React frontend.

## Technologies

**Backend:**
- Java 25
- Spring Boot 4.1.2
- Spring Framework 7.0
- Spring Web
- Spring Data Redis
- Spring for Apache Kafka (facade publishes visit events, visitor consumes them)
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

- JDK 25, installed where Maven can find it (`JAVA_HOME`, `~/.jdks` from IntelliJ, SDKMAN, `C:\Program Files\{Java, Amazon Corretto, Eclipse Adoptium, ...}`, `/usr/lib/jvm`, ...). Maven itself may run on any Java 17+: a Maven toolchain makes it compile, test and `spring-boot:run` with JDK 25 (`docs/012-jdk25-toolchain.md`)
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
The facade backend (`/api/hello`, serves the UI in production) runs on `http://localhost:8080`. Each `/api/hello` call publishes a visit event to the Kafka topic `fs-web.visits`; the visitor backend consumes it and increments the counter. If Kafka is down, the greeting is still returned (after at most about 2 s) and a warning is logged.

2. **Start the visitor backend** (Terminal 2):
```bash
./mvnw spring-boot:run -pl backends/visitor
```
The visitor backend (`/api/visits`) runs on `http://localhost:8081`

3. **Start the stocks backend** (Terminal 3):
```bash
./mvnw spring-boot:run -pl backends/stocks
```
The stocks backend (`/api/stocks`) runs on `http://localhost:8082`

4. **Start the frontend** (Terminal 4):
```bash
cd frontend
npm install  # Only needed first time
npm run dev
```
The frontend will run on `http://localhost:3000`

5. **Access the application** at `http://localhost:3000`

The Vite dev server proxies `/api/visits` to the visitor backend (8081), `/api/stocks` to the stocks backend (8082) and every other `/api/*` request to the facade backend (8080), giving you hot reload for both frontend and backend changes.

### Start everything with one command

Instead of steps 1–4 above (the first-time `./mvnw install -DskipTests` and `npm install` are still needed):

| Platform            | Command                | Backends only                  | Servers are stopped when                                                      |
|---------------------|------------------------|--------------------------------|-------------------------------------------------------------------------------|
| Linux / macOS / WSL | `scripts/dev.sh`       | `scripts/dev.sh --no-frontend` | You press Ctrl+C, the script is killed (SIGTERM/SIGHUP), or one server exits  |
| Windows             | `scripts\dev.cmd`      | `scripts\dev.cmd -NoFrontend`  | You press Ctrl+C in its window, close that window, or its process is killed   |

`dev.sh` runs all servers in one terminal with `[facade]`/`[visitor]`/`[stocks]`/`[frontend]` prefixes; `dev.cmd` opens one window per server. Details in `docs/010-dev-start-scripts.md`.

### Local Services (Docker Compose)

`backends/compose.yaml` defines the services used in development, shared by all backends:

| Service    | Image                         | Host port | Purpose                                                                         |
|------------|-------------------------------|-----------|---------------------------------------------------------------------------------|
| `valkey`   | `valkey/valkey:9.1.3-alpine`  | random    | Visit counter storage (Redis-compatible; the `redis` service is commented out)  |
| `lgtm`     | `grafana/otel-lgtm:0.32.1`    | 3001      | Grafana, Loki (logs), Tempo (traces), Mimir (metrics), OTel Collector           |
| `kafka`    | `apache/kafka:4.4.0`          | 9092      | Single-node Kafka (KRaft); topic `fs-web.visits` carries the visit events       |
| `mysql`    | `mysql:9.7.4`                 | random    | MySQL (database `fsweb`, user `fsweb`/`fsweb`, root password `root`)            |
| `postgres` | `postgres:18.6-alpine`        | random    | PostgreSQL (database `fsweb`, user `fsweb`/`fsweb`)                             |
| `dynamodb` | `amazon/dynamodb-local:3.3.2` | 8000      | Amazon DynamoDB Local (`-sharedDb -inMemory`), endpoint `http://localhost:8000` |

`./mvnw spring-boot:run -pl backends/facade` (or `-pl backends/visitor`, `-pl backends/stocks`) starts these containers automatically (via `spring-boot-docker-compose`) and connects the app to them, so no hosts, ports or OTLP endpoints are configured for development. All backends share the same containers; the first one started creates them. The containers keep running after the app stops (`lifecycle-management: start-only`).

- **Grafana:** `http://localhost:3001` (Explore → Tempo for traces, Loki for logs, Prometheus for metrics; service names `facade`, `visitor` and `stocks`)
- **Stop the containers:** `docker compose -p fs-web-springboot down` (add `-v` to also delete the MySQL/PostgreSQL data volumes)
- **Random host ports** (for a DB client): `docker compose -p fs-web-springboot port mysql 3306`, `... port postgres 5432`
- No backend uses `mysql`, `postgres` or `dynamodb` yet; they are ready for the next service. See [docs/008-mysql-postgres-dynamodb-compose.md](docs/008-mysql-postgres-dynamodb-compose.md) for how a backend connects to them.
- **Actuator:** `http://localhost:8080/actuator/health` (facade), `http://localhost:8081/actuator/health` (visitor), `http://localhost:8082/actuator/health` (stocks)

See [docs/001-docker-compose-observability.md](docs/001-docker-compose-observability.md) for the full design.

### Production Build

Build everything, then run one JAR per backend:

```bash
./mvnw clean install
java -jar backends/facade/target/facade-0.0.1-SNAPSHOT.jar      # UI + /api/hello on 8080
java -jar backends/visitor/target/visitor-0.0.1-SNAPSHOT.jar    # /api/visits on 8081
java -jar backends/stocks/target/stocks-0.0.1-SNAPSHOT.jar      # /api/stocks on 8082
```

Access the application at `http://localhost:8080`. The UI calls `/api/visits` and `/api/stocks` on its own origin, so outside the Vite dev server a reverse proxy (nginx, an ingress, an API gateway) has to route `/api/visits` to the visitor backend, `/api/stocks` to the stocks backend and everything else to the facade backend.

The packaged JARs do not include Docker Compose support. Run them with the `prod` profile and set the OTLP endpoint, the Kafka brokers for the facade and visitor backends (`KAFKA_BOOTSTRAP_SERVERS`, default `localhost:9092`), the downstream URLs the facade's `/api/overview` calls (`VISITOR_URL`, `STOCKS_URL`, defaults `http://localhost:8081` / `:8082`) and Redis for the visitor backend:

```bash
KAFKA_BOOTSTRAP_SERVERS=kafka.example:9092 VISITOR_URL=http://visitor.example:8081 STOCKS_URL=http://stocks.example:8082 \
  OTLP_ENDPOINT=http://collector:4318 \
  java -jar backends/facade/target/facade-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
KAFKA_BOOTSTRAP_SERVERS=kafka.example:9092 REDIS_HOST=valkey.example OTLP_ENDPOINT=http://collector:4318 \
  java -jar backends/visitor/target/visitor-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
OTLP_ENDPOINT=http://collector:4318 \
  java -jar backends/stocks/target/stocks-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

The Maven build automatically:
1. Installs Node.js and npm (via frontend-maven-plugin, in the `frontend` module)
2. Installs frontend dependencies
3. Builds the React app and packages it as the `fs-web-frontend` jar
4. Builds each backend (`facade`, `visitor`, `stocks`); the facade backend JAR includes the frontend jar

## Project Structure

```
fs-web-springboot/
├── pom.xml                # Parent + aggregator POM (shared versions and plugin settings)
├── backends/
│   ├── pom.xml            # Parent POM for all backends (shared backend build settings)
│   ├── compose.yaml       # Valkey, Grafana LGTM, Kafka, MySQL, PostgreSQL, DynamoDB Local (dev, shared by all backends)
│   ├── facade/            # Facade backend module (UI + /api/hello, port 8080)
│   │   ├── pom.xml
│   │   ├── src/main/java/       # Spring Boot backend code
│   │   └── src/main/resources/  # Backend configuration
│   ├── visitor/           # Visitor backend module (/api/visits, port 8081)
│   │   ├── pom.xml
│   │   ├── src/main/java/       # Visit counter (Valkey/Redis)
│   │   └── src/main/resources/  # Backend configuration
│   └── stocks/            # Stocks backend module (/api/stocks, port 8082)
│       ├── pom.xml
│       ├── src/main/java/       # In-memory stock list
│       └── src/main/resources/  # Backend configuration
├── frontend/              # React frontend module
│   ├── pom.xml            # Builds the React app into a jar of static resources
│   ├── src/               # React components and TypeScript
│   └── vite.config.ts     # Vite configuration with proxy
└── docs/                  # Design docs
```

### Maven modules

| Module          | Path               | ArtifactId        | Port | Output                                                                                      |
|-----------------|--------------------|-------------------|------|---------------------------------------------------------------------------------------------|
| Parent          | `.`                | `fs-web-parent`   |      | POM only                                                                                    |
| Frontend        | `frontend/`        | `fs-web-frontend` |      | Jar with the built React app in `static/`                                                   |
| Backends parent | `backends/`        | `fs-web-backends` |      | POM only                                                                                    |
| Facade backend  | `backends/facade`  | `facade`          | 8080 | Executable Spring Boot JAR: `/api/hello`, serves the frontend, publishes to `fs-web.visits` |
| Visitor backend | `backends/visitor` | `visitor`         | 8081 | Executable Spring Boot JAR: `/api/visits` (Valkey counter), consumes `fs-web.visits`        |
| Stocks backend  | `backends/stocks`  | `stocks`          | 8082 | Executable Spring Boot JAR: `/api/stocks` (in-memory list)                                  |

To add another backend, see [docs/004-multi-module-backends.md](docs/004-multi-module-backends.md); the visitor backend is described in [docs/007-visitor-service.md](docs/007-visitor-service.md), the stocks backend in [docs/009-stocks-service.md](docs/009-stocks-service.md) and the facade → Kafka → visitor visit events in [docs/011-kafka-visit-events.md](docs/011-kafka-visit-events.md) (which replaced the REST call of [docs/008-hello-counts-visits.md](docs/008-hello-counts-visits.md)).

## API Endpoints

| Endpoint                       | Backend   | Description                                                                                                           |
|--------------------------------|-----------|-----------------------------------------------------------------------------------------------------------------------|
| `GET /api/hello?name=YourName` | `facade`  | Returns a greeting message; also publishes a visit event to Kafka (`fs-web.visits`), which the visitor backend counts |
| `GET /api/visits`              | `visitor` | Returns the current visit count from Valkey (`{"count": n}`)                                                          |
| `POST /api/visits`             | `visitor` | Increments the visit count and returns the new value                                                                  |
| `GET /api/stocks`              | `stocks`  | Returns all stocks (`[{"symbol","name","price"}, …]`)                                                                 |
| `GET /api/stocks/{symbol}`     | `stocks`  | Returns one stock by symbol (case-insensitive); 404 if unknown                                                        |

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
./mvnw spring-boot:run -pl backends/stocks    # Run the stocks backend (8082)
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
