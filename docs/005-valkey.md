# Replace Redis with Valkey in Docker Compose — 2026-10-30

Branch: `chore/valkey` (created from `main`)

Goal: run Valkey instead of Redis for local development. The `redis` service stays in `backends/compose.yaml`, commented out, so switching back is a matter of swapping the comments.

## Changes

| File                    | Change                                                                               |
|-------------------------|--------------------------------------------------------------------------------------|
| `backends/compose.yaml` | `redis` service commented out; `valkey` service added (`valkey/valkey:9.1.3-alpine`) |
| `CLAUDE.md`             | Local services list and project tree mention `valkey`                                |
| `README.md`             | Local services table lists `valkey`                                                  |

No Java, POM or `application*.yml` changes. Valkey speaks the Redis protocol (RESP), so `spring-boot-starter-data-redis` (Lettuce), `StringRedisTemplate` and the `spring.data.redis.*` properties work unchanged.

## Service connection label

Spring Boot 4.1.2 creates `DataRedisConnectionDetails` only for containers whose image is `redis`, `redis/redis-stack` or `redis/redis-stack-server`. `valkey/valkey` is not on that list, so without help the app would start without a connection to it and fall back to `localhost:6379`.

The `valkey` service therefore sets the label Spring Boot documents for custom images:

```yaml
labels:
  org.springframework.boot.service-connection: redis
```

## Versions

| Item          | Before              | After                        | Notes                                      |
|---------------|---------------------|------------------------------|--------------------------------------------|
| Compose image | `redis:8.10-alpine` | `valkey/valkey:9.1.3-alpine` | 9.1.3 released 2026-10-26 (security fixes) |
| Service name  | `redis`             | `valkey`                     | Container `fs-web-springboot-valkey-1`     |

## Production

`application-prod.yml` is unchanged: `REDIS_HOST` / `REDIS_PORT` point at whatever Redis-compatible server runs in production (Redis or Valkey).

## Switching from an existing Redis container

The old `fs-web-springboot-redis-1` container is no longer in the Compose file and keeps running until removed. The visit count it holds is not copied to Valkey (the count restarts at 0).

```
docker compose -p fs-web-springboot down --remove-orphans
```

## Verification checklist

| Check                                                                 | Expected result                                                               |
|-----------------------------------------------------------------------|-------------------------------------------------------------------------------|
| `docker compose -f backends/compose.yaml config`                      | Valid file; services `valkey` and `lgtm`                                      |
| `./mvnw clean install`                                                | Build and tests pass (Docker Compose support is skipped in tests)             |
| `./mvnw spring-boot:run -pl backends/demo`                            | Log shows compose starting `valkey` and `lgtm`, then the app starts on 8080   |
| `curl -X POST http://localhost:8080/api/visits` (twice)               | Count goes up                                                                 |
| `http://localhost:8080/actuator/health`                               | `UP`, including a `redis` component (the health indicator name stays `redis`) |
| `docker exec fs-web-springboot-valkey-1 valkey-cli GET fs-web:visits` | Same count as the API                                                         |
