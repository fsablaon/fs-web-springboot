# Docker Compose: Redis and Grafana LGTM Observability

Status: implemented (2026-10-30)

## Goal

Run the app's local dependencies with Docker Compose, managed by Spring Boot's
`spring-boot-docker-compose` module:

- **Redis** for application data (a visit counter)
- **Grafana LGTM** (`grafana/otel-lgtm`) for logs, metrics and traces over OTLP

## How it works

`spring-boot-docker-compose` finds `compose.yaml` in the working directory when the
app starts (`mvn spring-boot:run`). It runs `docker compose up`, waits until the
containers accept TCP connections, and creates connection details from the container
image names:

| Container image     | Connection details created                                                                     |
|---------------------|------------------------------------------------------------------------------------------------|
| `redis`             | `DataRedisConnectionDetails` (host, port)                                                      |
| `grafana/otel-lgtm` | `OtlpTracingConnectionDetails`, `OtlpMetricsConnectionDetails`, `OtlpLoggingConnectionDetails` |

Because of this, no Redis host/port or OTLP endpoint properties are set for
development. The dependency is `optional`, so the Spring Boot Maven plugin leaves it
out of the packaged JAR.

## Versions

| Component                            | Version           | Notes                                                                     |
|--------------------------------------|-------------------|---------------------------------------------------------------------------|
| Spring Boot                          | 4.1.2 (was 4.0.0) | OTLP log export needs 4.0.1+; 4.1 adds `management.opentelemetry.enabled` |
| `opentelemetry-logback-appender-1.0` | 2.33.1-alpha      | Not managed by Boot; only alpha versions are published                    |
| `redis` image                        | 8.8-alpine        |                                                                           |
| `grafana/otel-lgtm` image            | 0.32.0            |                                                                           |

## Changes

### 1. `pom.xml`

| Dependency | Purpose |
|---|---|
| `spring-boot-docker-compose` (optional) | Starts compose services and creates connection details |
| `spring-boot-starter-data-redis` | Redis client (Lettuce) |
| `spring-boot-starter-opentelemetry` | Micrometer OTel tracing bridge and OTLP metrics registry |
| `spring-boot-starter-actuator` | Health, info and metrics endpoints |
| `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0` | Sends Logback events to the OTel SDK |

### 2. `compose.yaml` (project root)

| Service | Ports | Notes |
|---|---|---|
| `redis` | `6379` (random host port) | Spring Boot reads the mapped port from Docker |
| `lgtm` | `3001:3000`, `4317`, `4318` | Grafana is on host port 3001 because the Vite dev server uses 3000. OTLP ports use random host ports. |

### 3. `application.yml`

- `spring.docker.compose.lifecycle-management: start-only`: containers keep running
  after the app stops. Restarts are faster and Grafana keeps its data. Stop them with
  `docker compose down`.
- `management.tracing.sampling.probability: 1.0`: trace every request in development.
- `management.endpoints.web.exposure.include: health,info,metrics`

### 4. `application-prod.yml`

Used with `--spring.profiles.active=prod`. Docker Compose support is not in the JAR,
so endpoints come from environment variables:

| Variable | Default | Used for |
|---|---|---|
| `REDIS_HOST` | `localhost` | `spring.data.redis.host` |
| `REDIS_PORT` | `6379` | `spring.data.redis.port` |
| `OTLP_ENDPOINT` | `http://localhost:4318` | Base URL for `/v1/traces`, `/v1/metrics`, `/v1/logs` |
| `TRACING_SAMPLING_PROBABILITY` | `0.1` | `management.tracing.sampling.probability` |

### 5. Log export

- `src/main/resources/logback-spring.xml`: Spring Boot's console appender plus an
  `OpenTelemetryAppender` named `OTEL` on the root logger. File logging is not enabled
  (same as before).
- `config/OpenTelemetryLogbackConfig`: calls `OpenTelemetryAppender.install(...)` at
  startup with the auto-configured `OpenTelemetry` bean. It uses `ObjectProvider`, so it
  does nothing if no bean exists.

### 6. Redis visit counter

| Item | Description |
|---|---|
| `service/VisitCounterService` | `INCR` / `GET` on key `fs-web:visits` with `StringRedisTemplate` |
| `controller/VisitController` | `GET /api/visits` returns the count, `POST /api/visits` increments it. Response: `{"count": n}` |
| `dto/VisitCount` | Response record |
| `frontend/src/App.tsx` | Sends `POST /api/visits` once per page load and shows the count. Shows a message if Redis is unavailable. |

### 7. Tests

- Docker Compose support is skipped in tests by default (`spring.docker.compose.skip.in-tests=true`).
- `FsWebApplicationTests` sets `management.opentelemetry.enabled=false` and
  `management.otlp.metrics.export.enabled=false` so it does not try to reach a collector.
- Redis connects lazily, so `contextLoads()` passes without a Redis server.
- Not done yet: a Testcontainers integration test for Redis using `@ServiceConnection`.

### 8. Documentation

`README.md` and `CLAUDE.md` describe the services, ports, Grafana URL and production
profile.

## Verification checklist

| Check | Expected result |
|---|---|
| Docker Desktop running, then `mvn spring-boot:run` | Log shows compose starting `redis` and `lgtm`, then the app starts on 8080 |
| `curl -X POST http://localhost:8080/api/visits` (several times) | Count goes up |
| `http://localhost:8080/actuator/health` | `UP`, including a `redis` component |
| Grafana (`http://localhost:3001`) → Explore → Tempo | Traces for `/api/visits` (Redis spans if Lettuce observation is enabled in this Boot version) |
| Grafana → Explore → Loki | App logs, linked to trace IDs |
| Grafana → Explore → Prometheus | `http_server_requests_*` and JVM metrics |
| `mvn clean install` | Tests pass; JAR does not contain `spring-boot-docker-compose` |

## Possible next steps

- Testcontainers integration test for `VisitCounterService`
- Pin Grafana dashboards (JVM, HTTP) by mounting provisioning files into `lgtm`
- Redis-backed caching (`@EnableCaching`, `@Cacheable`)

## References

- [OpenTelemetry with Spring Boot (spring.io)](https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/)
- [Spring Boot reference: Docker Compose support](https://docs.spring.io/spring-boot/reference/features/dev-services.html)
- [Spring Boot 4.1 release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)
