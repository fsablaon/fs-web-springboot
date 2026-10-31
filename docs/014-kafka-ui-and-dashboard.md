# Kafka UI and a Grafana dashboard for the visit events topic — 2026-10-30

Branch: `feature/kafka-ui` (worktree `.worktrees/kafka-ui`, based on `main` at `a56e713`). Numbered 012 because 011 is `docs/011-kafka-visit-events.md` on the open `feature/kafka-visit-events` branch (PR #31); this change only touches `backends/compose.yaml` and new files, so the two branches merge in either order.

Goal: see what is in the Kafka topics (message count per topic, consumer lag, the messages themselves) without the CLI. Two views, because they answer different questions:

| View              | Where                                                                   | Source of truth                                      | Shows                                                                                                                                                    |
|-------------------|-------------------------------------------------------------------------|------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| Kafka UI (Kafbat) | `http://localhost:8090`                                                 | The broker                                           | Topics with partition offsets (= message count), consumer groups with lag, the JSON messages, topic configs; can create topics and produce test messages |
| Grafana dashboard | `http://localhost:3001` → Dashboards → `fs-web` → "Kafka: visit events" | The backends' Micrometer metrics, exported over OTLP | Published / consumed per topic over time, consumer lag over time, listener outcomes (success vs error), publish and process latency                      |

Kafka itself does not keep a message count. "Messages in a topic" is the end offset minus the beginning offset per partition, which equals the number of messages as long as retention (7 days by default) has not deleted segments and the topic is not compacted. `fs-web.visits` has one partition, so Kafka UI shows one number.

## Kafka UI

### Why the broker needed a second listener

A Kafka client first connects to a bootstrap address, then the broker tells it where to reconnect (the *advertised listener*). The `apache/kafka` image's built-in config advertises `PLAINTEXT://localhost:9092`, which is right for the backends on the host but wrong for another container: Kafka UI would connect to `kafka:9092`, be told "the broker is at localhost:9092", and talk to itself.

The broker now has two client listeners:

| Listener         | Bind     | Advertised as    | Used by                                               |
|------------------|----------|------------------|-------------------------------------------------------|
| `PLAINTEXT_HOST` | `:9092`  | `localhost:9092` | The backends on the host, Kafka CLI tools on the host |
| `PLAINTEXT`      | `:19092` | `kafka:19092`    | Other containers on the Compose network (`kafka-ui`)  |
| `CONTROLLER`     | `:29093` | —                | KRaft controller (internal)                           |

Setting any `KAFKA_*` variable on the image switches it from its built-in config to the variables, so the whole single-node KRaft config is spelled out (`KAFKA_NODE_ID`, `KAFKA_PROCESS_ROLES: broker,controller`, `KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:29093`, replication factor 1 for the internal topics, `CLUSTER_ID`). It is the image's own `single-node/plaintext` Compose example with the service renamed to `kafka`. Spring Boot's Docker Compose support is unaffected: it still reads the host port mapped to container port 9092.

`CLUSTER_ID` is fixed (`55Y4MlnrRHu8mxaq-tQaDA`, a base64url UUID as `kafka-storage random-uuid` prints one) so a restarted container accepts its existing log directory; without it the image would generate a new id on every start and refuse the old data.

### The `kafka-ui` service

| Setting                             | Value                            | Why                                                               |
|-------------------------------------|----------------------------------|-------------------------------------------------------------------|
| Image                               | `ghcr.io/kafbat/kafka-ui:v1.5.0` | Kafbat UI, the maintained fork of provectus kafka-ui              |
| Host port                           | `8090` → container `8080`        | 8080–8082 are the backends, 3001 Grafana, 8000 DynamoDB           |
| `KAFKA_CLUSTERS_0_NAME`             | `fs-web-springboot`              | Display name                                                      |
| `KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS` | `kafka:19092`                    | The in-network listener                                           |
| `DYNAMIC_CONFIG_ENABLED`            | `true`                           | Lets you add another cluster from the UI without editing the file |
| `depends_on`                        | `kafka`                          | Start order only; the UI retries until the broker answers         |

Where to look in the UI: **Topics → fs-web.visits → Overview** shows partitions with first/next offsets (next offset = message count on a fresh topic) and the count under "Messages"; **Messages** lists the JSON events with key, offset, timestamp and headers (including `traceparent`); **Consumers → visitor** shows the committed offset, end offset and lag per partition.

## Grafana dashboard

### Provisioning

`grafana/otel-lgtm` loads dashboards from `/otel-lgtm/grafana/conf/provisioning/dashboards/`. Two bind mounts on the `lgtm` service add ours (paths in `compose.yaml` are relative to `backends/`):

| Repo path                                       | Mounted at                                | Purpose                                                                   |
|-------------------------------------------------|-------------------------------------------|---------------------------------------------------------------------------|
| `backends/grafana/dashboards-provisioning.yaml` | `.../provisioning/dashboards/fs-web.yaml` | Dashboard provider: folder `fs-web`, re-read every 30 s, UI edits allowed |
| `backends/grafana/dashboards/` (directory)      | `.../provisioning/dashboards/fs-web/`     | One JSON file per dashboard; any new file in the directory is picked up   |

To add a dashboard: build it in the Grafana UI, Share → Export → "Export for sharing externally" off, save the JSON into `backends/grafana/dashboards/`, set a stable `uid`. Edits made in the UI to a provisioned dashboard are kept until the file changes, so export them back into the repo.

### What the dashboard shows

Datasource: the image's Prometheus (`uid: prometheus`). The collector forwards the backends' OTLP metrics to Prometheus's native OTLP endpoint, which turns Micrometer names into Prometheus names: dots become underscores, monotonic counters keep/gain a `_total` suffix, and a timer's unit is appended (`spring.kafka.listener` in milliseconds → `spring_kafka_listener_milliseconds_count` / `_sum`). The histogram queries use `__name__=~"...(_milliseconds)?_count"` so they also work if the unit suffix is not added.

| Panel                        | Query (simplified)                                                                                       | Note                                                                                                                                     |
|------------------------------|----------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| Published (facade)           | `sum by (topic) (increase(kafka_producer_topic_record_send_total[$__range]))`                            | Records the facade's producer sent in the selected time range                                                                            |
| Consumed (visitor)           | `sum by (topic) (increase(kafka_consumer_fetch_manager_records_consumed_total[$__range]))`               | Records the visitor's consumer fetched                                                                                                   |
| Consumer lag now / over time | `max by (topic, partition) (kafka_consumer_fetch_manager_records_lag)`                                   | 0 = caught up. Green/orange/red at 0/10/100                                                                                              |
| Listener failures            | `sum(increase(spring_kafka_listener_*_count{error!="none"}[$__range]))`                                  | Listener method threw. Deserialization failures are skipped *before* the listener (`ErrorHandlingDeserializer`) and are not counted here |
| Throughput                   | `rate(...send_total)` and `rate(...consumed_total)` per topic                                            | records/s                                                                                                                                |
| Listener outcomes            | `sum by (messaging_source_name, error) (rate(spring_kafka_listener_*_count))`                            | Stacked, by error tag                                                                                                                    |
| Average latency              | `rate(*_sum) / rate(*_count)` of `spring_kafka_template` (publish) and `spring_kafka_listener` (process) | Publish includes waiting for the broker ack                                                                                              |
| Totals since app start       | Raw `kafka_producer_topic_record_send_total` and `..._records_consumed_total`                            | Counters reset when the app restarts; the "increase" stats above do not have that problem                                                |

Variable `topic`: `label_values(kafka_producer_topic_record_send_total, topic)`, multi-select, default All. It is built from the producer's metric on purpose: the Kafka consumer reports every topic twice, once as `fs-web.visits` and once as `fs-web_visits` (the client's own metric-name sanitizing), and filtering the consumer series with the producer's names drops the duplicate.

The metric names were taken from what the backends actually export: both JARs were run with `management.otlp.metrics.export.url` pointing at a small OTLP/HTTP capture server (protobuf decoded with `opentelemetry-proto`) while `/api/hello` was called. Relevant series and their tags:

| Micrometer name (as exported)                           | Service | Kind      | Tags                                                                                                                          |
|---------------------------------------------------------|---------|-----------|-------------------------------------------------------------------------------------------------------------------------------|
| `kafka.producer.topic.record.send.total`                | facade  | counter   | `topic`, `client.id`, `spring.id`, `kafka.version`                                                                            |
| `kafka.consumer.fetch.manager.records.consumed.total`   | visitor | counter   | `topic` (twice, see above), `client.id`, `spring.id`                                                                          |
| `kafka.consumer.fetch.manager.records.lag` / `.lag.max` | visitor | gauge     | `topic`, `partition`, `client.id`, `spring.id`                                                                                |
| `spring.kafka.template` (ms)                            | facade  | histogram | `messaging.destination.name`, `messaging.operation=publish`, `error`, `spring.kafka.template.name`                            |
| `spring.kafka.listener` (ms)                            | visitor | histogram | `messaging.source.name`, `messaging.kafka.consumer.group`, `messaging.operation=process`, `error`, `spring.kafka.listener.id` |

Resource attribute `service.name` (`facade` / `visitor`) is promoted to the `service_name` label by the image's Prometheus config.

### Limits of the dashboard

- Application-side only: records produced with the CLI or Kafka UI, or consumed by another group, are not in these metrics. Broker-side numbers are in Kafka UI.
- The `spring.kafka.*` observation series only exist because `spring.kafka.template.observation-enabled` and `spring.kafka.listener.observation-enabled` are `true` (PR #31). On `main` without PR #31 the backends export no Kafka metrics at all and the dashboard is empty.
- Metrics arrive every 60 s (`management.otlp.metrics.export.step` default), so a stat can lag a minute behind Kafka UI.

## Files changed

| File                                                  | Change                                                                                                                                                                                                                     |
|-------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `backends/compose.yaml`                               | `kafka`: explicit KRaft config with `PLAINTEXT_HOST` (localhost:9092) and `PLAINTEXT` (kafka:19092) listeners, fixed `CLUSTER_ID`; new `kafka-ui` service on 8090; `lgtm`: two read-only mounts for dashboard provisioning |
| `backends/grafana/dashboards-provisioning.yaml`       | New: Grafana dashboard provider (folder `fs-web`, 30 s refresh, UI edits allowed)                                                                                                                                          |
| `backends/grafana/dashboards/kafka-visit-events.json` | New: dashboard `fs-web-kafka-visits` ("Kafka: visit events"), 10 panels, `topic` variable, link to Kafka UI                                                                                                                |
| `README.md`, `CLAUDE.md`                              | Compose services table / list, Kafka UI URL, dashboard location                                                                                                                                                            |
| `docs/014-kafka-ui-and-dashboard.md`                  | This file                                                                                                                                                                                                                  |

No changes to any backend or the frontend.

## Behavior to be aware of

- **The Kafka container is recreated** the first time a backend starts with this `compose.yaml` (Spring Boot runs `docker compose up`, and the service definition changed). The container has no volume, so the topic and its messages are gone; the visit counter lives in Valkey and is not affected. The visitor backend recreates the topic at startup.
- Running `docker compose -p fs-web-springboot down` and starting again also loses the messages (same as before this change). Add a volume on `/tmp/kraft-combined-logs` if that matters.
- Kafka UI connects through the Compose network only; it does not need, and does not use, port 9092.
- Grafana keeps a UI edit of a provisioned dashboard until the JSON file changes; the file in the repo is the source of truth.

## Verification

Docker is not available in the container this was written in, so the Compose stack was not started here. What was checked:

| Check                                                           | Result                                                                                                                                                                                                                                     |
|-----------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `compose.yaml` and `dashboards-provisioning.yaml` parse as YAML | OK; `kafka` advertises `PLAINTEXT_HOST://localhost:9092,PLAINTEXT://kafka:19092`, `kafka-ui` bootstraps from `kafka:19092`                                                                                                                 |
| Kafka broker config                                             | Same variables as the `apache/kafka` repo's `docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml`, service renamed                                                                                               |
| Kafbat UI image tag and variables                               | `v1.5.0` is the latest release tag on ghcr.io; `KAFKA_CLUSTERS_0_NAME`, `KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS`, `DYNAMIC_CONFIG_ENABLED` from the Kafbat docs                                                                                 |
| otel-lgtm provisioning path and datasource uid                  | `/otel-lgtm/grafana/conf/provisioning/dashboards/` and `uid: prometheus` from the `grafana/docker-otel-lgtm` repo (`grafana-datasources.yaml`); metrics go collector → Prometheus OTLP endpoint (`otelcol-config.yaml`, `prometheus.yaml`) |
| Metric names                                                    | Captured from the running facade and visitor JARs (PR #31 build) over OTLP, see table above                                                                                                                                                |
| All 12 PromQL expressions in the dashboard                      | Parse with `promql-parser` after substituting the Grafana variables the way the Prometheus datasource does                                                                                                                                 |

Still to check on a machine with Docker Desktop:

- `./mvnw spring-boot:run -pl backends/visitor` (recreates the `kafka` container, starts `kafka-ui`), then `-pl backends/facade`; the visitor log should show the topic being created, the facade's `/api/hello` should still publish (`localhost:9092` unchanged)
- `http://localhost:8090`: cluster `fs-web-springboot` online, topic `fs-web.visits`, consumer group `visitor` with lag 0 after a few `/api/hello` calls; the messages tab shows the JSON events
- `http://localhost:3001` → Dashboards → `fs-web` → "Kafka: visit events": "Published" and "Consumed" equal the number of `/api/hello` calls in the time range (within the 60 s export step), lag 0
- If a panel shows "No data", check the actual series names in Grafana → Explore → Metrics browser (search `kafka_producer`, `spring_kafka`) against the table above; the name translation is the one assumption not verified against a running Prometheus
