# Multi-module Maven build for multiple backends — 2026-10-30

Branch: `chore/multi-module-backends` (created from `chore/add-maven-wrapper`)

Goal: turn the single-module project into a Maven multi-module build so more Spring Boot backends can be added next to the existing one, sharing versions, build settings, the React frontend and the local Docker Compose services.

## Layout

```
fs-web-springboot/
├── pom.xml                  # org.fsweb:fs-web-parent (packaging pom): root parent + aggregator
├── frontend/
│   └── pom.xml              # org.fsweb:fs-web-frontend (jar of static/**)
└── backends/
    ├── pom.xml              # org.fsweb:fs-web-backends (packaging pom): backend parent + aggregator
    ├── compose.yaml         # Redis + Grafana LGTM, shared by all backends
    └── demo/
        ├── pom.xml          # org.fsweb:demo (executable Spring Boot JAR)
        └── src/             # moved from ./src with git mv (history kept)
```

## Modules

| Module          | Path            | ArtifactId        | Parent                             | Output                                         |
|-----------------|-----------------|-------------------|------------------------------------|------------------------------------------------|
| Root parent     | `.`             | `fs-web-parent`   | `spring-boot-starter-parent` 4.1.2 | POM only                                       |
| Frontend        | `frontend/`     | `fs-web-frontend` | `fs-web-parent`                    | Jar with the built React app in `static/`      |
| Backends parent | `backends/`     | `fs-web-backends` | `fs-web-parent`                    | POM only                                       |
| Demo backend    | `backends/demo` | `demo`            | `fs-web-backends`                  | `backends/demo/target/demo-0.0.1-SNAPSHOT.jar` |

Reactor order: `fs-web-parent` → `fs-web-frontend` → `fs-web-backends` → `demo` (the backend depends on the frontend jar).

## What lives where

| Setting                                                         | Location                                    | Notes                                                           |
|-----------------------------------------------------------------|---------------------------------------------|-----------------------------------------------------------------|
| Java 25, encoding                                               | Root `pom.xml` `<properties>`               | Inherited by every module                                       |
| Node/npm versions, `frontend-maven-plugin` version              | Root `pom.xml`                              | Executions are in `frontend/pom.xml`                            |
| Skip `spring-boot:run` for the root POM                         | Root `<build><plugins>`                     | `inherited=false`, applies to the root only                     |
| `opentelemetry-logback-appender-1.0` version                    | `backends/pom.xml`                          | Pinned to 2.28.1-alpha to match the OTel SDK Boot 4.1.2 manages |
| `fs-web-frontend` version                                       | `backends/pom.xml` `<dependencyManagement>` | Backends add it without a version                               |
| Lombok (provided) and `spring-boot-starter-test` (test)         | `backends/pom.xml` `<dependencies>`         | Inherited by every backend                                      |
| Lombok annotation processor (`maven-compiler-plugin`)           | `backends/pom.xml` `<build><plugins>`       | Inherited by every backend                                      |
| `spring-boot-maven-plugin`: Lombok excluded, `workingDirectory` | `backends/pom.xml` `<pluginManagement>`     | Each backend declares the plugin to get the repackaged JAR      |
| Skip `spring-boot:run` for the backends POM                     | `backends/pom.xml` `<build><plugins>`       | `inherited=false`, applies to `backends/pom.xml` only           |
| Backend starters                                                | `backends/<name>/pom.xml`                   | Each backend picks its own                                      |

## How the frontend is shared

The React build used to write into the backend's `src/main/resources/static/`. With several backends that no longer works, so the frontend is now its own module:

| Before                                                     | After                                                                          |
|------------------------------------------------------------|--------------------------------------------------------------------------------|
| `vite.config.ts` `outDir: '../src/main/resources/static'`  | `outDir: 'target/classes/static'` (inside `frontend/`)                         |
| Node/npm installed to `./target/node` by the backend build | Installed to `frontend/target/node` by the `frontend` module                   |
| React files copied into the backend JAR as resources       | `fs-web-frontend` jar (contains `static/**`) is a runtime dependency of `demo` |
| `.gitignore` rule for `src/main/resources/static/`         | Removed; output is under `frontend/target/`, already ignored by `target/`      |

`WebConfig` is unchanged: it serves `classpath:/static/` and falls back to `/static/index.html`, which resolves from the frontend jar. A backend that does not serve the UI leaves out the `fs-web-frontend` dependency.

`frontend/eslint.config.js` now ignores `target/` so `npm run lint` does not scan the built bundle or the Node install.

## Docker Compose

`compose.yaml` moved from the project root to `backends/compose.yaml`. Each backend points at it with:

```yaml
spring:
  docker:
    compose:
      file: backends/compose.yaml
```

The path is relative to the working directory, and every launcher starts the app in the project root:

| Launcher                                 | Working directory | Why                                                                                                                          |
|------------------------------------------|-------------------|------------------------------------------------------------------------------------------------------------------------------|
| `./mvnw spring-boot:run` (root or `-pl`) | Project root      | `backends/pom.xml` sets `<workingDirectory>${maven.multiModuleProjectDirectory}</workingDirectory>` (the folder with `.mvn`) |
| IntelliJ Spring Boot run configuration   | Project root      | IntelliJ's default when the configuration sets no working directory                                                          |

`backends/compose.yaml` sets `name: fs-web-springboot`. Without it, Docker Compose would name the project after the folder (`backends`) and start a second set of containers next to the ones created before the move, and the second LGTM container would fail on host port 3001. With a fixed name, the containers can be stopped from anywhere with `docker compose -p fs-web-springboot down`.

History: a first version set `spring.docker.compose.file: ../../compose.yaml`, which worked with Maven (module folder as working directory) but failed in IntelliJ with `'files' content [..\..\compose.yaml] must exist` because IntelliJ starts in the project root. Setting the Maven working directory to the project root makes both launchers resolve paths the same way.

Docker Compose support is skipped in tests and excluded from the packaged JAR.

## Commands

| Task                                      | Command                                                     |
|-------------------------------------------|-------------------------------------------------------------|
| Build everything                          | `./mvnw clean install`                                      |
| First-time setup before running a backend | `./mvnw install -DskipTests`                                |
| Run the demo backend                      | `./mvnw spring-boot:run -pl backends/demo`                  |
| Build one backend and what it needs       | `./mvnw install -pl backends/demo -am`                      |
| Build all backends (and the frontend)     | `./mvnw install -pl backends -am -amd`                      |
| Run all tests                             | `./mvnw test`                                               |
| Run one test                              | `./mvnw test -pl backends/demo -Dtest=ClassName#methodName` |
| Run the production JAR                    | `java -jar backends/demo/target/demo-0.0.1-SNAPSHOT.jar`    |
| Stop the Compose containers               | `docker compose -p fs-web-springboot down`                  |

`spring-boot:run` first runs the lifecycle up to `test-compile` for each selected module:

- From the root, `./mvnw spring-boot:run` builds the frontend (`npm install` + `npm run build`) and then starts `demo`. The root POM, `backends/pom.xml` and the frontend module skip the `run` goal. With several backends, the root form starts them one after another and blocks on the first, so use `-pl`.
- With `-pl backends/demo`, the frontend is not built. Maven takes `fs-web-frontend` from `frontend/target/classes` if an earlier build left it there, otherwise from `~/.m2`. On a fresh clone, run `./mvnw install -DskipTests` once first.

`-Dtest=...` needs `-pl`: without it, Surefire fails in modules that have no matching test.

## Adding a backend

1. Create `backends/<name>/pom.xml`:

   ```xml
   <parent>
       <groupId>org.fsweb</groupId>
       <artifactId>fs-web-backends</artifactId>
       <version>0.0.1-SNAPSHOT</version>
   </parent>

   <artifactId><name></artifactId>

   <dependencies>
       <!-- starters this backend needs; add fs-web-frontend (runtime) only if it serves the UI.
            Lombok and spring-boot-starter-test come from backends/pom.xml. -->
   </dependencies>

   <build>
       <plugins>
           <plugin>
               <groupId>org.springframework.boot</groupId>
               <artifactId>spring-boot-maven-plugin</artifactId>
           </plugin>
       </plugins>
   </build>
   ```

2. Add `<module><name></module>` to `backends/pom.xml`.
3. Use its own base package (for example `org.fsweb.<name>`) and its own `@SpringBootApplication` class.
4. Set a different `server.port` in its `application.yml` if it runs at the same time as `demo` (8080).
5. If it uses Docker Compose services, add the `spring-boot-docker-compose` optional dependency and `spring.docker.compose.file: backends/compose.yaml`, and add any new services to `backends/compose.yaml`. All backends share the same `fs-web-springboot` Compose project and containers.
6. If the React app calls it in dev mode, add a proxy entry for its path in `frontend/vite.config.ts` (today `/api` goes to 8080).

## Files changed

| File                                               | Change                                                                                           |
|----------------------------------------------------|--------------------------------------------------------------------------------------------------|
| `pom.xml`                                          | Rewritten as root parent + aggregator; skips `spring-boot:run` for itself                        |
| `backends/pom.xml`                                 | Added: backend parent + aggregator with the backend-only build settings and shared dependencies  |
| `backends/demo/pom.xml`                            | Added (backend dependencies moved from the old root POM)                                         |
| `frontend/pom.xml`                                 | Added (frontend-maven-plugin moved from the old root POM)                                        |
| `src/**` → `backends/demo/src/**`                  | Moved with `git mv`                                                                              |
| `compose.yaml` → `backends/compose.yaml`           | Moved with `git mv`; added `name: fs-web-springboot`                                             |
| `backends/demo/src/main/resources/application.yml` | `spring.docker.compose.file: backends/compose.yaml`; updated stop command                        |
| `frontend/vite.config.ts`                          | `outDir` → `target/classes/static`                                                               |
| `frontend/eslint.config.js`                        | Ignores `target`                                                                                 |
| `.gitignore`                                       | Removed `src/main/resources/static/` rule                                                        |
| `README.md`, `CLAUDE.md`                           | Commands, paths and structure updated                                                            |
| `docs/*.md`                                        | Prefixed with a three-digit sequence number (`001-` … `004-`) in the order the changes were made |

## Verification

Ran on a copy of the project with Maven 3.9.16 (wrapper) and JDK 21, passing `-Djava.version=21 -Dmaven.compiler.source=21 -Dmaven.compiler.target=21` because JDK 25 was not available in that environment.

| Check                                                 | Result                                                                                                                                                       |
|-------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `./mvnw clean install`                                | `fs-web-springboot` → `fs-web-frontend` → `fs-web-backends` → `demo`, all SUCCESS                                                                            |
| Frontend jar                                          | `fs-web-frontend-0.0.1-SNAPSHOT.jar` contains `static/index.html` and `static/assets/*`                                                                      |
| Demo JAR                                              | Contains `BOOT-INF/lib/fs-web-frontend-0.0.1-SNAPSHOT.jar`; Lombok excluded                                                                                  |
| Tests                                                 | `FsWebApplicationTests`: 1 run, 0 failures                                                                                                                   |
| `java -jar`: `GET /`, assets, SPA route, `/api/hello` | React `index.html`; 200 `text/javascript`; 200 `text/html`; `Hello, France!`                                                                                 |
| `npm run lint` with build output in `target/`         | Passes                                                                                                                                                       |
| `./mvnw spring-boot:run` (root)                       | Root and backends POMs and frontend skip `run`; `demo` starts; `/` and `/api/hello` respond                                                                  |
| `./mvnw spring-boot:run -pl backends/demo`            | `demo` starts                                                                                                                                                |
| Docker Compose file lookup                            | `Using Docker Compose file <root>/backends/compose.yaml` for root `spring-boot:run`, `-pl`, and a plain `java` launch from the project root (IntelliJ-style) |

Still to check on a JDK 25 machine:

- `./mvnw clean install` with the default `java.version=25`
- `./mvnw spring-boot:run -pl backends/demo` and the IntelliJ run configuration with Docker running, to confirm Redis and LGTM start and `/actuator/health` is `UP` (pulling images from Docker Hub was blocked during verification, so the app ran with `--spring.docker.compose.enabled=false` and Redis down)
- In IntelliJ, reload the Maven project (the old single `demo` module becomes four modules)

## Notes

- The root `target/` folder from earlier single-module builds (including `target/node`) is no longer used; `./mvnw clean` removes it.
- `docs/001-docker-compose-observability.md` describes the setup before this change (single module, `compose.yaml` in the root).
