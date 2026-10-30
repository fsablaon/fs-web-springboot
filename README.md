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
- Maven

**Frontend:**
- React 19
- TypeScript
- Vite
- CSS Modules

## Prerequisites

- JDK 25 or later
- Maven 3.6+
- Node.js 26+ and npm 12+ (for frontend development)
- Docker Desktop (or another Docker engine with `docker compose`), running before you start the backend

## Getting Started

### Development Mode (Recommended)

For development with hot reload:

1. **Start the backend** (Terminal 1):
```bash
mvn spring-boot:run
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

`compose.yaml` defines the services used in development:

| Service | Image | Purpose |
|---------|-------|---------|
| `redis` | `redis:8.10-alpine` | Visit counter storage |
| `lgtm` | `grafana/otel-lgtm:0.32.1` | Grafana, Loki (logs), Tempo (traces), Mimir (metrics), OTel Collector |

`mvn spring-boot:run` starts these containers automatically (via `spring-boot-docker-compose`) and connects the app to them, so no hosts, ports or OTLP endpoints are configured for development. The containers keep running after the app stops (`lifecycle-management: start-only`).

- **Grafana:** `http://localhost:3001` (Explore → Tempo for traces, Loki for logs, Prometheus for metrics)
- **Stop the containers:** `docker compose down`
- **Actuator:** `http://localhost:8080/actuator/health`

See [docs/docker-compose-observability.md](docs/docker-compose-observability.md) for the full design.

### Production Build

Build and run the complete application as a single JAR:

```bash
mvn clean install
java -jar target/demo-0.0.1-SNAPSHOT.jar
```

Access the application at `http://localhost:8080`

The packaged JAR does not include Docker Compose support. Run it with the `prod` profile and set the Redis and OTLP endpoints:

```bash
REDIS_HOST=redis.example OTLP_ENDPOINT=http://collector:4318 \
  java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

The Maven build automatically:
1. Installs Node.js and npm (via frontend-maven-plugin)
2. Installs frontend dependencies
3. Builds the React app
4. Bundles it into the Spring Boot JAR

## Project Structure

```
fs-web-springboot/
├── src/main/java/          # Spring Boot backend code
├── src/main/resources/     # Backend configuration
├── docs/                   # Design docs
├── frontend/               # React frontend code
│   ├── src/               # React components and TypeScript
│   └── vite.config.ts     # Vite configuration with proxy
├── compose.yaml           # Redis + Grafana LGTM for local development
└── pom.xml                # Maven configuration
```

## API Endpoints

- `GET /api/hello?name=YourName` - Returns a greeting message
- `GET /api/visits` - Returns the current visit count from Redis (`{"count": n}`)
- `POST /api/visits` - Increments the visit count and returns the new value

## Running Tests

### Backend Tests
```bash
mvn test
```

### Frontend Tests
```bash
cd frontend
npm run lint
```

## Additional Commands

### Backend Only
```bash
mvn spring-boot:run      # Run backend
mvn clean               # Clean build artifacts
mvn package             # Package as JAR
```

### Frontend Only
```bash
cd frontend
npm run dev             # Start dev server
npm run build           # Build for production
npm run preview         # Preview production build
npm run lint            # Run ESLint
```
