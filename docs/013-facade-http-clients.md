# 013 – One configurable HTTP client per downstream service in the facade

## Summary

The facade is the backend that fronts the others, so it is the one that will accumulate HTTP clients to downstream services. Each downstream needs its own settings — base URL, connect/read timeouts, later TLS bundles or API-key headers — and its own connection pool, so a slow service cannot starve calls to the others.

This change introduces the pattern and uses it for the two services the facade can reach today: the facade now exposes `GET /api/overview`, which reads the current visit count from the visitor backend (`GET /api/visits`) and the stock list from the stocks backend (`GET /api/stocks`) and returns both in one response. The `/api/hello` → Kafka flow from `docs/011-kafka-visit-events.md` is untouched.

The mechanism is Spring Boot 4's **HTTP service client groups**: each downstream is an `@HttpExchange` interface registered in a named group; Boot builds one `RestClient` per group and binds the group's settings from `spring.http.serviceclient.<group>.*` in `application.yml`. Adding a downstream is one interface, one annotation line and one YAML block.

## What was added

| File                                                             | Role                                                                                                                                                |
|------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| `backends/facade/pom.xml`                                        | `spring-boot-starter-restclient` (RestClient + the HTTP service client auto-configuration)                                                          |
| `client/VisitorApi.java`, `client/VisitCount.java`               | `@HttpExchange("/api/visits")` with `@GetExchange VisitCount current()`; Spring generates the implementation                                        |
| `client/StocksApi.java`, `client/Stock.java`                     | `@HttpExchange("/api/stocks")` with `all()` and `bySymbol(symbol)`                                                                                  |
| `client/VisitorClient.java`, `client/StocksClient.java`          | Thin wrappers that own each downstream's failure policy: log and return empty (`OptionalLong` / `Optional<List<Stock>>`), never throw to the caller |
| `config/HttpServiceClientsConfig.java`                           | `@ImportHttpServices(group = "visitor", …)` and `@ImportHttpServices(group = "stocks", …)` — one line per downstream service                        |
| `controller/OverviewController.java`, `dto/Overview.java`        | `GET /api/overview` → `{"visits": n, "stocks": [...]}`; a field is `null` when that service is unavailable, the endpoint still answers 200           |
| `application.yml`                                                | `spring.http.clients.*` (defaults for every client) and `spring.http.serviceclient.{visitor,stocks}.*` (per-group base URL and timeouts)            |
| `application-prod.yml`                                           | `VISITOR_URL` and `STOCKS_URL` feed the two groups' `base-url`                                                                                      |
| `controller/OverviewControllerTests.java`                        | Real clients and real `VisitorApi` / `StocksApi` proxies over two `MockRestServiceServer`s: both up, each one down, both down                       |
| `config/HttpServiceClientsConfigTests.java`                      | `@SpringBootTest`: both interfaces are beans and each group binds its own base URL and read timeout from `application.yml`                          |

## Configuration

```yaml
spring:
  http:
    clients:                      # defaults for every HTTP client the facade builds
      connect-timeout: 1s
      read-timeout: 2s
      redirects: dont-follow
    serviceclient:                # one block per group
      visitor:
        base-url: http://localhost:8081
        read-timeout: 1s          # the count is informational; keep /api/overview fast
      stocks:
        base-url: http://localhost:8082
        read-timeout: 3s          # a real quote source would be slower than a counter
```

Per-group keys Boot 4.1 binds (`HttpClientProperties`):

| Key                        | Purpose                                                                 |
|----------------------------|-------------------------------------------------------------------------|
| `base-url`                 | Prefix for every request the group's interfaces make                    |
| `connect-timeout`          | TCP connect timeout                                                     |
| `read-timeout`             | Time to wait for the response                                           |
| `redirects`                | `follow` / `dont-follow`                                                |
| `ssl.bundle`               | Name of a `spring.ssl.bundle.*` for client TLS / custom trust material |
| `default-header.<name>`    | Header(s) sent on every request, e.g. an API key                        |
| `cookie-handling`          | Whether the client keeps cookies                                        |
| `apiversion.*`             | API version insertion (header, query parameter or path segment)         |

Anything a property cannot express (an interceptor, a custom error handler, a header computed at runtime) goes in a `RestClientHttpServiceGroupConfigurer` bean that switches on the group name.

## How it is wired

| Step | Who                                                  | What                                                                                                                                                                                                                                       |
|------|------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1    | `@ImportHttpServices` (Spring Framework 7)           | Registers the group and a proxy bean for each listed interface (`VisitorApi`, `StocksApi`)                                                                                                                                                 |
| 2    | `HttpServiceClientAutoConfiguration` (Boot)          | Supplies two `RestClientHttpServiceGroupConfigurer`s: one applies the `spring.http.serviceclient.<group>` properties and builds the group's request factory through `ClientHttpRequestFactoryBuilder`; the other applies every `RestClientCustomizer` bean |
| 3    | `RestClientCustomizer`s                              | Includes the observation customizer from Actuator, so group clients are traced like the auto-configured `RestClient.Builder`: the `traceparent` header reaches the downstream and all spans of `/api/overview` share one trace in Grafana     |
| 4    | `VisitorClient` / `StocksClient`                     | Call the proxy and decide what the facade does when the call fails                                                                                                                                                                         |
| 5    | `OverviewController`                                 | Combines the two answers; a missing one becomes `null`                                                                                                                                                                                     |

Each group gets its own `RestClient.Builder` and request factory. Timeouts, pools and TLS are therefore isolated per downstream, which is the point of the exercise.

## Adding a downstream service

1. Add an `@HttpExchange` interface under `org.fsweb.facade.client` (one method per endpoint, `@GetExchange` / `@PostExchange` …), plus the response records it needs.
2. Add `@ImportHttpServices(group = "<name>", types = <Interface>.class)` to `HttpServiceClientsConfig`.
3. Add `spring.http.serviceclient.<name>` to `application.yml`, and the production URL (as an environment variable) to `application-prod.yml`.
4. Wrap the interface in a small `@Component` that owns the failure policy for that service, as `VisitorClient` and `StocksClient` do. Keep "what to do when the call fails" out of the HTTP configuration.
5. Unit-test the wrapper with `MockRestServiceServer` bound to a `RestClient.Builder`, and build the proxy with `HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))`.

## Alternatives considered

| Option                                                                                        | Why not                                                                                                                 |
|-----------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| One `@ConfigurationProperties` record and one `@Qualifier`-ed `RestClient` bean per service   | Works for two or three services, but each one is three copied files and the client names live in `@Qualifier` strings  |
| Map-based `facade.clients.<name>` properties and a factory that builds a `RestClient` per entry | Same result as the chosen option, but with merge, validation and factory code the project would have to own            |
| Boot 4 HTTP service client groups (chosen)                                                    | Built for this problem, per-group settings from YAML, no custom factory, observation applied by Boot                    |

## Operational notes

- New endpoint `GET /api/overview` is served by the facade (8080); the Vite proxy already sends it there (`/api/*` default), and no reverse-proxy rule changes are needed in production.
- Production: `VISITOR_URL` and `STOCKS_URL` (defaults `http://localhost:8081` / `:8082`) in the `prod` profile, next to `KAFKA_BOOTSTRAP_SERVERS`.
- Numbered `013`: `011` is the Kafka visit-events design and `012` the JDK 25 toolchain.
- Docs on this feature: Spring Boot 4.1 reference, *Calling REST Services → HTTP Service Clients*.
