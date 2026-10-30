# Visit events over Kafka: facade publishes, visitor consumes — 2026-10-30

Branch: `feature/kafka-visit-events` (worktree `.worktrees/kafka-visit-events`, based on `main` at `bc923a6`, after PR #29)

Goal: the facade backend no longer calls the visitor backend over HTTP to record a visit. Each `GET /api/hello` publishes a `VisitEvent` to the Kafka topic `fs-web.visits`; the visitor backend consumes the topic and increments the counter in Valkey. This replaces the synchronous `POST /api/visits` call from `docs/008-hello-counts-visits.md`.

## How it works

```
browser ──GET /api/hello──▶ facade (8080) ──VisitEvent (JSON)──▶ Kafka topic fs-web.visits ──▶ visitor (8081) ──INCR fs-web:visits──▶ Valkey
                              │                                                                    │
                              └──────────────── same trace (traceparent record header) ────────────┘
```

| Step | What happens                                                                                                                                                             |
|------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1    | `HelloController.hello()` calls `VisitEventPublisher.publish("/api/hello")` and returns the greeting; the response body is unchanged, so the frontend needs no change    |
| 2    | `VisitEventPublisher` builds a `VisitEvent` (UUID, source `facade`, path, `Instant.now()`) and calls `KafkaTemplate.send(topic, key = source, event)`                    |
| 3    | The send is asynchronous: the request thread does not wait for the broker's acknowledgement. Success is logged at DEBUG, failure at WARN; `/api/hello` still answers 200 |
| 4    | The visitor backend's `VisitEventListener` (`@KafkaListener`, consumer group `visitor`) receives the event and calls `VisitCounterService.increment()`                   |
| 5    | `GET /api/visits` (visitor) returns the new count, as before. `POST /api/visits` still exists; the frontend uses it on page load                                         |

### Wire format

Topic `fs-web.visits`, key `facade` (the `source`), value JSON:

```json
{"id":"00159247-3a60-4853-a7cb-ddb468701a45","source":"facade","path":"/api/hello","occurredAt":"2026-10-30T20:51:11.892771Z"}
```

| Field        | Type            | Meaning                                                                                                             |
|--------------|-----------------|---------------------------------------------------------------------------------------------------------------------|
| `id`         | UUID string     | Unique per event; lets a consumer detect redelivery (not done yet)                                                  |
| `source`     | string          | Service that recorded the visit; also the record key, so all events from one service land on one partition in order |
| `path`       | string          | Request path that counted as a visit                                                                                |
| `occurredAt` | ISO-8601 string | When the request was handled (UTC)                                                                                  |

The facade (`org.fsweb.facade.event.VisitEvent`) and the visitor (`org.fsweb.visitor.event.VisitEvent`) each have their own record with these fields. The facade sends no `__TypeId__` header (`spring.json.add.type.headers: false`) and the visitor ignores type headers (`spring.json.use.type.headers: false`, `spring.json.value.default.type`), so the two class names are independent and there is still no shared module between backends.

## Design choices

| Choice                                                                               | Reason                                                                                                                                                                                                                                          |
|--------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Kafka event instead of `POST /api/visits`                                            | The facade no longer depends on the visitor backend being up or fast; events wait in the topic until the visitor consumes them (`auto-offset-reset: earliest`, so the first start of the visitor also counts events published before it joined) |
| `spring-boot-starter-kafka` in both backends                                         | Boot 4 starter; `KafkaTemplate`, `@KafkaListener`, `KafkaAdmin` auto-configured. `spring-boot-starter-restclient` and the `client/` package were removed from the facade                                                                        |
| `JacksonJsonSerializer` / `JacksonJsonDeserializer` (Spring Kafka 4)                 | Boot 4 ships Jackson 3 (`tools.jackson`) only; the older `JsonSerializer`/`JsonDeserializer` need Jackson 2 databind, which is not on the classpath                                                                                             |
| `ErrorHandlingDeserializer` around the JSON deserializer (visitor)                   | A record that is not valid JSON would otherwise be retried forever and block the partition. With the wrapper the `DefaultErrorHandler` logs it and skips it (`DeserializationException` is not retried); verified in `VisitEventListenerTests`  |
| Fire-and-forget send, failures logged at WARN                                        | Same contract as before: the counter is secondary to the greeting. `max.block.ms: 2000` bounds how long `send()` blocks on the request thread when the broker is unreachable (default 60 s)                                                     |
| `spring.kafka.admin.operation-timeout: 2s` + `default.api.timeout.ms: 2000` (facade) | The template observation looks up the cluster id once through `KafkaAdmin` (retried every 5 min after a failure). Without these, the first `/api/hello` with Kafka down took 62 s in the test below; with them about 2.5 s                      |
| `observation-enabled: true` on template (facade) and listener (visitor)              | The `traceparent` header is added to each record and read by the listener, so the visitor's span continues the `/api/hello` trace (same trace ID in both logs below), as the RestClient call did                                                |
| Topic created by the visitor (`NewTopic` bean, 1 partition)                          | The visitor owns the visit counter, so it owns the topic. `KafkaAdmin` creates it at startup if missing and leaves it alone otherwise. The dev broker also auto-creates topics on first write, so the facade can start first                    |
| Consumer group `visitor`                                                             | One group, so each event is counted once however many visitor instances run                                                                                                                                                                     |
| Record key = `source`                                                                | Keeps the facade's events on one partition in order; a second producer (another service) would get its own key                                                                                                                                  |

Not done (possible follow-ups): deduplication by `id` in the visitor (at-least-once delivery can redeliver after a consumer crash between `increment()` and the offset commit); a dead-letter topic instead of log-and-skip; a shared `VisitEvent` module.

## Configuration

### Facade

| Property                                                        | `application.yml`                                                    | `prod` profile                              |
|-----------------------------------------------------------------|----------------------------------------------------------------------|---------------------------------------------|
| `spring.kafka.bootstrap-servers`                                | from Docker Compose (`apache/kafka` image, host port 9092)           | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` |
| `spring.kafka.producer.key-serializer`                          | `StringSerializer`                                                   | inherited                                   |
| `spring.kafka.producer.value-serializer`                        | `org.springframework.kafka.support.serializer.JacksonJsonSerializer` | inherited                                   |
| `spring.kafka.producer.properties.spring.json.add.type.headers` | `false`                                                              | inherited                                   |
| `spring.kafka.producer.properties.max.block.ms`                 | `2000`                                                               | inherited                                   |
| `spring.kafka.template.observation-enabled`                     | `true`                                                               | inherited                                   |
| `spring.kafka.admin.operation-timeout`                          | `2s`                                                                 | inherited                                   |
| `spring.kafka.admin.properties.default.api.timeout.ms`          | `2000`                                                               | inherited                                   |
| `facade.visits.topic`                                           | `fs-web.visits`                                                      | inherited                                   |
| `facade.visits.source`                                          | `facade`                                                             | inherited                                   |

`facade.visits.*` binds to `VisitEventProperties` (enabled on `FacadeApplication`). The `facade.visitor.*` block and `VISITOR_URL` are gone.

### Visitor

| Property                                                                    | `application.yml`                                                        | `prod` profile                              |
|-----------------------------------------------------------------------------|--------------------------------------------------------------------------|---------------------------------------------|
| `spring.kafka.bootstrap-servers`                                            | from Docker Compose                                                      | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` |
| `spring.kafka.consumer.auto-offset-reset`                                   | `earliest`                                                               | inherited                                   |
| `spring.kafka.consumer.key-deserializer`                                    | `StringDeserializer`                                                     | inherited                                   |
| `spring.kafka.consumer.value-deserializer`                                  | `org.springframework.kafka.support.serializer.ErrorHandlingDeserializer` | inherited                                   |
| `spring.kafka.consumer.properties.spring.deserializer.value.delegate.class` | `org.springframework.kafka.support.serializer.JacksonJsonDeserializer`   | inherited                                   |
| `spring.kafka.consumer.properties.spring.json.use.type.headers`             | `false`                                                                  | inherited                                   |
| `spring.kafka.consumer.properties.spring.json.value.default.type`           | `org.fsweb.visitor.event.VisitEvent`                                     | inherited                                   |
| `spring.kafka.listener.observation-enabled`                                 | `true`                                                                   | inherited                                   |
| `visitor.visits.topic`                                                      | `fs-web.visits`                                                          | inherited                                   |
| `visitor.visits.group-id`                                                   | `visitor`                                                                | inherited                                   |
| `visitor.visits.partitions`                                                 | `1`                                                                      | inherited                                   |

`visitor.visits.*` binds to `VisitEventProperties` (enabled in `KafkaTopicConfig`).

## Files changed

| File                                                                                  | Change                                                                                                       |
|---------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| `backends/facade/pom.xml`                                                             | `spring-boot-starter-restclient` → `spring-boot-starter-kafka`; `spring-kafka-test` (test); description      |
| `backends/facade/src/main/java/org/fsweb/facade/FacadeApplication.java`               | `@EnableConfigurationProperties(VisitEventProperties.class)`                                                 |
| `backends/facade/src/main/java/org/fsweb/facade/event/VisitEvent.java`                | New: the event record                                                                                        |
| `backends/facade/src/main/java/org/fsweb/facade/event/VisitEventProperties.java`      | New: `facade.visits.*` (topic, source)                                                                       |
| `backends/facade/src/main/java/org/fsweb/facade/event/VisitEventPublisher.java`       | New: `publish(path)` → `KafkaTemplate.send`, async, failures logged                                          |
| `backends/facade/src/main/java/org/fsweb/facade/controller/HelloController.java`      | Calls `visitEventPublisher.publish("/api/hello")` instead of `visitorClient.recordVisit()`                   |
| `backends/facade/src/main/java/org/fsweb/facade/client/*` (3 files)                   | Removed: `VisitorClient`, `VisitorProperties`, `VisitCount`                                                  |
| `backends/facade/src/main/java/org/fsweb/facade/config/VisitorClientConfig.java`      | Removed                                                                                                      |
| `backends/facade/src/main/resources/application.yml`                                  | `spring.kafka.producer/template/admin` block, `facade.visits` block; `facade.visitor` removed                |
| `backends/facade/src/main/resources/application-prod.yml`                             | `spring.kafka.bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:...}`; `facade.visitor.base-url` removed          |
| `backends/facade/src/test/java/org/fsweb/facade/controller/HelloControllerTests.java` | Rewritten: mock `KafkaTemplate`; 3 tests (event content, failed future, thrown exception)                    |
| `backends/facade/src/test/java/org/fsweb/facade/event/VisitEventPublisherTests.java`  | New: `@EmbeddedKafka`; reads the record back as a string and checks key, JSON fields, no type header         |
| `backends/visitor/pom.xml`                                                            | `spring-boot-starter-kafka`; `spring-kafka-test` (test); description                                         |
| `backends/visitor/src/main/java/org/fsweb/visitor/event/VisitEvent.java`              | New: the event record (visitor's copy)                                                                       |
| `backends/visitor/src/main/java/org/fsweb/visitor/event/VisitEventProperties.java`    | New: `visitor.visits.*` (topic, group id, partitions)                                                        |
| `backends/visitor/src/main/java/org/fsweb/visitor/event/VisitEventListener.java`      | New: `@KafkaListener` → `VisitCounterService.increment()`                                                    |
| `backends/visitor/src/main/java/org/fsweb/visitor/config/KafkaTopicConfig.java`       | New: `NewTopic` bean, enables `VisitEventProperties`                                                         |
| `backends/visitor/src/main/resources/application.yml`                                 | `spring.kafka.consumer/listener` block, `visitor.visits` block                                               |
| `backends/visitor/src/main/resources/application-prod.yml`                            | `spring.kafka.bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:...}`                                             |
| `backends/visitor/src/test/java/org/fsweb/visitor/VisitorApplicationTests.java`       | `spring.kafka.listener.auto-startup=false`, `spring.kafka.admin.auto-create=false` (no broker in test)       |
| `backends/visitor/src/test/java/org/fsweb/visitor/event/VisitEventListenerTests.java` | New: `@EmbeddedKafka`, mocked `VisitCounterService`; 3 facade-style records → 3 increments; bad JSON skipped |
| `README.md`, `CLAUDE.md`                                                              | Kafka in the tech list, endpoint table, module tables, `KAFKA_BOOTSTRAP_SERVERS`, structure tree             |
| `docs/011-kafka-visit-events.md`                                                      | This file                                                                                                    |

No changes to the stocks backend, the frontend, `compose.yaml` or the dev scripts (the `kafka` service was already in `compose.yaml`).

## Behavior to be aware of

- The first `/api/hello` after the facade starts is slower (about 1.8 s in the test below): the producer is created lazily and fetches metadata, and the template observation looks up the cluster id once.
- With Kafka down, each `/api/hello` still answers 200 but takes about 2 s (`max.block.ms`) and logs `WARN ... Could not publish visit event ...: Topic fs-web.visits not present in metadata after 2000 ms.` Those visits are lost (not queued on the facade side).
- The visitor backend's `KafkaAdmin` creates the topic at startup; if the broker is not ready yet it logs an error and continues (`fail-fast: false`), and the listener keeps retrying the connection in the background.
- Delivery is at-least-once: if the visitor crashes after `increment()` but before the offset is committed, the event is counted twice on restart.
- `POST /api/visits` is unchanged and still used by the frontend on page load, so a reload plus one "Get Greeting" click still adds 2 to the counter.

## Verification

Run in a Linux container with OpenJDK 21 (no JDK 25 could be installed there), so the build used `-Djava.version=21 -Dmaven.compiler.source=21 -Dmaven.compiler.target=21`; the code uses no Java 22+ features. Docker is not available there: Docker Compose support was disabled with `--spring.docker.compose.enabled=false`, OTLP export was turned off, a local Redis on 6379 stood in for Valkey and a KRaft broker started from `spring-kafka-test`'s `EmbeddedKafkaKraftBroker` (random port) stood in for the `kafka` container. The frontend module was not built (its jar was stubbed).

| Check                                                                                | Result                                                                                                                                |
|--------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `./mvnw clean install -pl backends,backends/facade,backends/visitor,backends/stocks` | All 4 modules SUCCESS                                                                                                                 |
| Facade tests                                                                         | `FacadeApplicationTests` 1/1, `HelloControllerTests` 3/3, `VisitEventPublisherTests` 1/1 (embedded Kafka)                             |
| Visitor tests                                                                        | `VisitorApplicationTests` 1/1, `VisitEventListenerTests` 2/2 (embedded Kafka; bad record logged by `DefaultErrorHandler` and skipped) |
| Stocks tests                                                                         | 7/7, unchanged                                                                                                                        |
| Both JARs running: `GET /api/visits` on 8081                                         | `{"count":0}`                                                                                                                         |
| `GET /api/hello?name=France`, `?name=A`, `?name=B` on 8080                           | `Hello, France!` (1.81 s, first call), `Hello, A!` (17 ms), `Hello, B!` (21 ms)                                                       |
| `GET /api/visits` on 8081 after that                                                 | `{"count":3}`; Redis key `fs-web:visits` = 3                                                                                          |
| Facade log                                                                           | `Published visit event 00159247-… to topic fs-web.visits` (DEBUG) ×3                                                                  |
| Visitor log                                                                          | `Visit event 00159247-… from facade (/api/hello at 2026-10-30T20:51:11.892771Z) counted, count is now 1` / `2` / `3` (DEBUG)          |
| Trace propagation                                                                    | Trace ID `7d42758067b5f964e1d67d467fe8a371` in the facade's publish line and in the visitor's listener line for the same event        |
| Broker stopped, `GET /api/hello?name=down`                                           | `Hello, down!` HTTP 200 in 2.02 s; `WARN ... Could not publish visit event ...`; count stays 3                                        |
| Facade started with no broker at all, first `GET /api/hello`                         | 62.5 s before the admin timeouts were added; 2.56 s after (`WARN ...: Topic fs-web.visits not present in metadata after 2000 ms.`)    |

Still to check on a machine with JDK 25 and Docker Desktop:

- `./mvnw clean install` (full build including the frontend module, compiled for Java 25)
- `./mvnw spring-boot:run -pl backends/facade` and `-pl backends/visitor` (or `scripts/dev.sh` / `scripts\dev.cmd`): the `kafka` container must be reachable as `localhost:9092`; then click "Get Greeting" on `http://localhost:3000` and reload: the counter should go up by 2 per reload + click
- `docker compose -p fs-web-springboot exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic fs-web.visits --from-beginning` shows the JSON events
- In Grafana (`http://localhost:3001`, Explore → Tempo), one trace for `GET /api/hello` should contain the facade's `fs-web.visits send` span and the visitor's `fs-web.visits process` span
