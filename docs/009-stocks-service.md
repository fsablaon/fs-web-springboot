# Stocks backend: a new service for /api/stocks — 2026-10-30

Branch: `feature/stocks-service` (created from `main` after the visitor backend, PR #24; rebased onto `main` after PRs #25 and #27), worktree `.worktrees/stocks-service`

Goal: add a third Spring Boot backend, `stocks`, that owns `/api/stocks`. This first version serves a small in-memory list of stocks (symbol, name, price) with no external calls, so the module, routing and build are in place and the data source can be swapped later. The module follows the steps in `docs/004-multi-module-backends.md` and mirrors `backends/visitor` (`docs/007-visitor-service.md`).

## Modules after this change

| Module          | Path               | ArtifactId | Port | Owns                                           | Serves the UI |
|-----------------|--------------------|------------|------|------------------------------------------------|---------------|
| Facade backend  | `backends/facade`  | `facade`   | 8080 | `GET /api/hello`                               | Yes           |
| Visitor backend | `backends/visitor` | `visitor`  | 8081 | `GET /api/visits`, `POST /api/visits` (Valkey) | No            |
| Stocks backend  | `backends/stocks`  | `stocks`   | 8082 | `GET /api/stocks`, `GET /api/stocks/{symbol}`  | No            |

Reactor order: `fs-web-parent` → `fs-web-frontend` → `fs-web-backends` → `facade` → `visitor` → `stocks`.

## API

| Endpoint                   | Response                                                                                   |
|----------------------------|--------------------------------------------------------------------------------------------|
| `GET /api/stocks`          | `200` — JSON array of `{"symbol","name","price"}`, sorted by symbol                        |
| `GET /api/stocks/{symbol}` | `200` — one stock; the symbol is trimmed and upper-cased, so `/api/stocks/nvda` works      |
| unknown symbol             | `404` — RFC 9457 problem detail: `{"status":404,"title":"Not Found","detail":"Stock not found: ZZZZ","instance":"/api/stocks/ZZZZ"}` |

`price` is a `BigDecimal` serialised as a JSON number (`255.10`).

## The stocks module

```
backends/stocks/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/org/fsweb/stocks/
    │   │   ├── StocksApplication.java
    │   │   ├── config/OpenTelemetryLogbackConfig.java      # same as facade's and visitor's
    │   │   ├── controller/StockController.java             # GET /api/stocks, GET /api/stocks/{symbol}
    │   │   ├── dto/Stock.java                              # record (symbol, name, price)
    │   │   ├── exception/StockNotFoundException.java
    │   │   ├── exception/GlobalExceptionHandler.java       # @RestControllerAdvice → 404 ProblemDetail
    │   │   └── service/StockService.java                   # in-memory ConcurrentHashMap seeded with 6 stocks
    │   └── resources/
    │       ├── application.yml                             # port 8082, Docker Compose file, name "stocks"
    │       ├── application-prod.yml                        # OTLP_ENDPOINT, TRACING_SAMPLING_PROBABILITY
    │       └── logback-spring.xml                          # console + OTEL appender
    └── test/java/org/fsweb/stocks/
        ├── StocksApplicationTests.java                     # context loads
        ├── controller/StockControllerTest.java             # RANDOM_PORT + RestClient: list, one, 404
        └── service/StockServiceTest.java                   # plain unit tests
```

| Item                      | Value                                                                                                                                   |
|---------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| Parent                    | `org.fsweb:fs-web-backends` (Lombok, `spring-boot-starter-test`, compiler and Boot plugin settings inherited)                           |
| Dependencies              | `spring-boot-starter-web`, `-actuator`, `-opentelemetry`, `opentelemetry-logback-appender-1.0`, `spring-boot-docker-compose` (optional) |
| Not a dependency          | `fs-web-frontend` (only `facade` serves the UI); `spring-boot-starter-data-redis` (no data store yet)                                   |
| `spring.application.name` | `stocks` — also the OpenTelemetry `service.name`, so its traces and logs are separate from `facade` and `visitor` in Grafana           |
| `server.port`             | 8082 (`facade` 8080, `visitor` 8081)                                                                                                    |
| Docker Compose            | `spring.docker.compose.file: backends/compose.yaml`, `lifecycle-management: start-only`; shares the `fs-web-springboot` Compose project |
| Logging                   | `org.fsweb.stocks: DEBUG` in dev, `INFO` in the `prod` profile                                                                          |

`StockService` holds the data in a `ConcurrentHashMap` seeded in its constructor. To move to a real source (market-data API, database, Valkey), replace the map behind `findAll()` / `findBySymbol()` and keep the controller and tests. The seeded prices are placeholders, not live quotes.

The service does not use Valkey or Kafka, so Spring Boot creates no Redis or Kafka connection for it even though `backends/compose.yaml` starts both; it only uses the LGTM stack for telemetry.

## Routing /api/stocks to the stocks backend

| Mode                           | Who serves the UI | How `/api/stocks` reaches `stocks`                                                                                                                    |
|--------------------------------|-------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| Dev (`npm run dev`, port 3000) | Vite              | `frontend/vite.config.ts`: `'/api/stocks'` → `http://localhost:8082`, listed with `'/api/visits'` before the catch-all `'/api'` → `http://localhost:8080` |
| Production JARs                | `facade` on 8080  | Not handled here (same as `/api/visits`): a reverse proxy in front of the JARs routes `/api/stocks` to 8082                                           |

The React app does not call `/api/stocks` yet; the proxy entry is in place for when it does.

## Files changed

| File                          | Change                                                                            |
|-------------------------------|-----------------------------------------------------------------------------------|
| `backends/pom.xml`            | `<module>stocks</module>` added                                                   |
| `backends/stocks/**`          | New module (see above)                                                            |
| `frontend/vite.config.ts`     | Proxy entry for `/api/stocks` → 8082                                              |
| `README.md`                   | Four-terminal dev setup, module/endpoint tables, production run commands          |
| `CLAUDE.md`                   | Module table, backends list, structure tree, commands, endpoint-ownership note    |
| `docs/009-stocks-service.md`  | This file (numbered 009: `main` already had two `008-*` docs from PRs #25 and #27) |

## Verification

The machine-side shell available during this change had only JDK 11 and no Docker, so the build ran in a Linux container with OpenJDK 21.0.12 and Maven 3.9.11 (the wrapper's Maven download was blocked there), with `-Djava.version=21 -Dmaven.compiler.release=21` as in `docs/004`. Docker Compose was disabled with `--spring.docker.compose.enabled=false` and OTLP export turned off.

| Check                                                              | Result                                                                                                                             |
|--------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------|
| `mvn clean install -pl backends/stocks -am`                        | `fs-web-springboot` → `fs-web-backends` → `stocks`, all SUCCESS (`-am` pulls in only the two parent POMs; `stocks` has no frontend dependency) |
| Tests                                                              | `StocksApplicationTests` 1/1, `StockControllerTest` 3/3, `StockServiceTest` 3/3                                                    |
| `stocks-0.0.1-SNAPSHOT.jar` contents                               | No `fs-web-frontend`, Lombok or `spring-boot-docker-compose`                                                                       |
| `java -jar stocks…jar`: `/actuator/health`                         | `{"status":"UP"}` on 8082                                                                                                          |
| `GET /api/stocks`                                                  | `200`, 6 stocks sorted AAPL … TSLA                                                                                                 |
| `GET /api/stocks/nvda`                                             | `200`, `{"symbol":"NVDA","name":"NVIDIA Corporation","price":187.60}`                                                              |
| `GET /api/stocks/ZZZZ`                                             | `404`, `{"detail":"Stock not found: ZZZZ","instance":"/api/stocks/ZZZZ","status":404,"title":"Not Found"}`                         |
| Log service name                                                   | `[stocks]`                                                                                                                         |

Still to check on the Windows machine (JDK 25, Docker Desktop):

- `./mvnw.cmd clean install` from the project root (full reactor, Java 25)
- `./mvnw.cmd spring-boot:run -pl backends/stocks`: starts on 8082, reuses the `fs-web-springboot` Compose containers, `/actuator/health` is `UP`, traces/logs appear in Grafana under service `stocks`
- `npm run dev` with the stocks backend running: `http://localhost:3000/api/stocks` returns the list through the Vite proxy
- In IntelliJ, reload the Maven project so `stocks` appears as a module
