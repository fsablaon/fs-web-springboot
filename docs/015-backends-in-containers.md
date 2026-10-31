# 015 – Running the backends in Docker containers

## Summary

Until now the three backends ran only as processes on the developer machine (`./mvnw spring-boot:run`, or `java -jar` of the packaged JARs), while everything they talk to — Valkey, Kafka, the LGTM stack, the databases — already ran in containers from `backends/compose.yaml`. This change adds the missing piece: the backends themselves can be built into images and started next to those services with one command.

```bash
docker compose -f backends/compose.yaml --profile apps up --build -d    # build images, start everything
open http://localhost:8088                                               # UI through the gateway
docker compose -p fs-web-springboot down                                 # stop everything
```

Nothing changes for the existing development flow: `./mvnw spring-boot:run -pl backends/<name>` still starts only the infrastructure services and runs the backend on the host.

## What was added

| File                                   | Role                                                                                                                                                                                                                  |
|----------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `backends/Dockerfile`                  | One multi-stage Dockerfile for all backends: a single Maven build stage, one layer-extraction stage per backend, one runtime **target** per backend (`facade`, `visitor`, `stocks`)                                   |
| `.dockerignore`                        | Keeps `target/`, `node_modules/`, `.git`, `.worktrees`, `.vm`, docs and scripts out of the build context                                                                                                               |
| `backends/compose.yaml`                | New services `facade`, `visitor`, `stocks` and `gateway` under the Compose profile `apps`; they use the `kafka` service's in-network listener `kafka:19092`                                                           |
| `backends/gateway/nginx.conf`          | Reverse proxy that routes `/api/visits` → visitor, `/api/stocks` → stocks, everything else → facade (the same table as `frontend/vite.config.ts`)                                                                    |
| `README.md`, `CLAUDE.md`               | "Run in containers" section and the new files                                                                                                                                                                         |

## Design

### How the images are built

```
                     backends/Dockerfile (context = project root)
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │ build            eclipse-temurin:25-jdk-noble                                │
 │                  COPY . .  →  ./mvnw -B -ntp -DskipTests package             │
 │                  (frontend jar + facade/visitor/stocks JARs, one reactor run) │
 ├──────────────┬──────────────────┬──────────────────┬─────────────────────────┤
 │ extract-*    │ facade JAR       │ visitor JAR      │ stocks JAR              │
 │              │ java -Djarmode=tools -jar application.jar extract --layers    │
 ├──────────────┼──────────────────┼──────────────────┼─────────────────────────┤
 │ targets      │ facade  :8080    │ visitor :8081    │ stocks  :8082           │
 │              │ eclipse-temurin:25-jre-alpine, user app, java -jar            │
 └──────────────┴──────────────────┴──────────────────┴─────────────────────────┘
```

| Decision                                                   | Why                                                                                                                                                                                                                                                                                                                                                   |
|------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Dockerfile, not `spring-boot:build-image` (Buildpacks)     | Buildpacks need Docker *during the Maven build*, pull a builder image of several hundred MB, and the result is hard to inspect or tweak. A Dockerfile is plain text, builds with `docker compose build` alone, and uses the same Maven wrapper as everything else. Buildpacks remain an option for CI (`./mvnw spring-boot:build-image -pl backends/facade`) |
| One Dockerfile with a **target per backend**               | The three backends share a parent POM and the facade needs the frontend jar, so one `./mvnw package` of the whole reactor is simpler and cheaper than three separate `-pl <module> -am` builds. BuildKit builds the shared `build` stage once; `docker compose build` then just picks a different target per service                                    |
| Build inside Docker (not "copy a JAR built on the host")   | Only Docker is required; the image does not depend on what happens to be in `target/` on the machine, and the build is the same locally and in CI. The cost — Maven and npm downloads — is paid once thanks to BuildKit cache mounts (`/root/.m2`, `/root/.npm`, `sharing=locked`)                                                                     |
| Build stage on Ubuntu (`-noble`), runtime on Alpine         | `frontend-maven-plugin` downloads the glibc build of Node, which does not run on Alpine (musl). The runtime only needs a JRE, so it uses the smaller Alpine image. Maven runs on the image's JDK 25, so `maven-toolchains-plugin:select-jdk-toolchain` selects it without a `toolchains.xml`                                                            |
| Layered JARs (`extract --layers`)                          | Spring Boot's layer tools split the JAR into `dependencies`, `spring-boot-loader`, `snapshot-dependencies` and `application`. Copied in that order, a code change invalidates only the last (KB-sized) image layer; dependencies are shared between the three images' layers                                                                           |
| `-DskipTests` in the image build                           | Tests run in `./mvnw clean install` on the developer machine and in CI. Running them again inside every image build would double the build time and needs an embedded Kafka in the build container                                                                                                                                                     |
| Non-root user, JRE only, no shell entrypoint               | Standard hardening: `adduser -S app`, `USER app`, `ENTRYPOINT ["java","-jar","application.jar"]`. JVM flags are passed with `JAVA_TOOL_OPTIONS`, so the Dockerfile stays environment-neutral                                                                                                                                                           |

### How the containers are configured

The containers run the packaged JARs the way a deployment would: Docker Compose support is not in the JAR, so the `prod` profile is active (`SPRING_PROFILES_ACTIVE=prod`) and every address comes from the environment variables `application-prod.yml` already reads. No Java or YAML change was needed.

| Service   | Environment                                                                                                                                                       |
|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `facade`  | `KAFKA_BOOTSTRAP_SERVERS=kafka:19092`, `VISITOR_URL=http://visitor:8081`, `STOCKS_URL=http://stocks:8082`, `OTLP_ENDPOINT=http://lgtm:4318`                       |
| `visitor` | `REDIS_HOST=valkey`, `REDIS_PORT=6379`, `KAFKA_BOOTSTRAP_SERVERS=kafka:19092`, `OTLP_ENDPOINT=http://lgtm:4318`                                                   |
| `stocks`  | `OTLP_ENDPOINT=http://lgtm:4318`                                                                                                                                  |
| all three | `SPRING_PROFILES_ACTIVE=prod`, `TRACING_SAMPLING_PROBABILITY=1.0` (prod default is 0.1), `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`, memory limit 768 MB      |

Other points:

- **Compose profile `apps`.** Spring Boot's Docker Compose support runs `docker compose up` without a profile, so the app services are invisible to `./mvnw spring-boot:run`; only `--profile apps` starts them. One file keeps one Compose project (`fs-web-springboot`) and one network, so containerized backends reuse the same Valkey/Kafka/LGTM containers as host-run ones.
- **Same host ports as the dev flow** (8080/8081/8082). A backend runs either on the host or as a container; starting both fails on the port, which is better than two instances competing for the same Kafka group.
- **Health checks** hit `/actuator/health` with the Alpine image's `wget`. Visitor's health includes Redis and Kafka, so it turns unhealthy when they are unreachable.
- **`depends_on`** only orders start-up; the backends already tolerate a slow broker (`max.block.ms`, consumer reconnects) and a missing downstream (`/api/overview` returns `null` fields).

### Kafka: a second listener

A Kafka broker tells clients where to reconnect (its *advertised listener*). A broker that advertised only `localhost:9092` would be right for a backend on the host and wrong for a backend in a container (for it, `localhost` is itself). The `kafka` service therefore has two client listeners:

| Listener         | Address          | Used by                                                                            |
|------------------|------------------|------------------------------------------------------------------------------------|
| `PLAINTEXT_HOST` | `localhost:9092` | Backends on the host via `spring-boot:run` (Spring Boot still discovers host port 9092), Kafka CLI tools |
| `PLAINTEXT`      | `kafka:19092`    | Containers on the Compose network: the `apps` profile and `kafka-ui`                 |

The in-network listener was introduced together with Kafka UI (`docs/014-kafka-ui-and-dashboard.md`), which needs it for the same reason; the `apps` profile reuses it, no broker change was needed here.

### Gateway

The UI calls `/api/visits` and `/api/stocks` on its own origin. In development the Vite dev server proxies them; in production the README asks for a reverse proxy. The `gateway` service is that proxy for the containerized setup: nginx on `http://localhost:8088` with three `location` blocks, longest prefix first. `8088` was chosen because 8080–8082 are the backends, 8090 is Kafka UI, 8180 is Keycloak (`feature/keycloak-oauth`), 8000 is DynamoDB Local and 3001 is Grafana.

## Commands

| Task                                     | Command                                                                                        |
|------------------------------------------|------------------------------------------------------------------------------------------------|
| Build images and start everything        | `docker compose -f backends/compose.yaml --profile apps up --build -d`                         |
| Start without rebuilding                 | `docker compose -f backends/compose.yaml --profile apps up -d`                                 |
| Rebuild one image after a code change    | `docker compose -f backends/compose.yaml --profile apps up --build -d stocks`                  |
| Follow a backend's logs                  | `docker compose -p fs-web-springboot logs -f facade`                                           |
| Status / health                          | `docker compose -p fs-web-springboot ps`                                                       |
| Stop only the backends, keep the services | `docker compose -f backends/compose.yaml --profile apps stop facade visitor stocks gateway`   |
| Stop and remove everything               | `docker compose -p fs-web-springboot down` (add `-v` to delete the database volumes)           |
| Build one image without Compose          | `docker build -f backends/Dockerfile --target visitor -t fs-web/visitor:local .`               |

Entry points once up: UI and all APIs through the gateway at `http://localhost:8088`; the backends directly on `http://localhost:8080`, `:8081`, `:8082` (`/actuator/health` on each); Grafana at `http://localhost:3001` with traces, metrics and logs of all three (service names `facade`, `visitor`, `stocks`).

## Verification

Docker is not available in the linked VM this change was written in, so the image build and the Compose start-up were **not** run there. What was checked:

- `./mvnw clean install -pl backends/stocks -am` passes (nothing in the Maven build changed; the Docker files are outside it).
- The Dockerfile's extraction step, run by hand on the built `stocks` JAR with the VM's JDK 25: `java -Djarmode=tools -jar application.jar extract --layers --destination extracted` produces `dependencies/lib` (74 jars; `spring-boot-docker-compose` and Lombok are not among them), `spring-boot-loader`, `snapshot-dependencies` and `application/application.jar`; copied together as the runtime stage does, `java -jar application.jar --spring.profiles.active=prod` starts (OTLP export fails only because no collector was running).
- `backends/compose.yaml` parses, and the profile holds exactly `facade`, `visitor`, `stocks`, `gateway`; the environment variable names match `application-prod.yml`.

On a machine with Docker Desktop:

```bash
docker compose -f backends/compose.yaml --profile apps up --build -d
docker compose -p fs-web-springboot ps          # all four app services (healthy)
curl http://localhost:8088/api/hello            # publishes a visit event
curl http://localhost:8088/api/visits           # count went up (via gateway -> visitor)
curl http://localhost:8088/api/overview         # {"visits": n, "stocks": [...]}
./mvnw spring-boot:run -pl backends/stocks      # after `docker compose stop stocks`: host-run still works with the new broker config
```

## Not done / next steps

- **Faster start-up**: a training run in the Dockerfile to produce a CDS archive or a JDK 25 AOT cache (`-XX:AOTCacheOutput`), see Spring Boot's "Efficient deployments" docs. Adds build time; worth it once start-up time matters.
- **CI**: build and push the three images from GitHub Actions (`docker build --target ...` with a registry cache). The Dockerfile needs no change for that.
- **Frontend-only dev against containers**: `npm run dev` already proxies to 8080–8082, so it works unchanged against the containerized backends.
