# MySQL, PostgreSQL and DynamoDB Local in Docker Compose — 2026-10-30

Branch: `feature/mysql-postgres-compose` (created from `main`)

Goal: make MySQL, PostgreSQL and Amazon DynamoDB Local available to every backend in local development, started the same way as Valkey, LGTM and Kafka (`./mvnw spring-boot:run -pl backends/<name>`). No backend uses them yet; this change only adds the containers and documents how a backend connects.

## Changes

| File                    | Change                                                                                                                    |
|-------------------------|---------------------------------------------------------------------------------------------------------------------------|
| `backends/compose.yaml` | `mysql`, `postgres` and `dynamodb` services added; named volumes `mysql-data` and `postgres-data`; header comment updated |
| `README.md`             | Local services table lists all six services with host ports; notes on volumes, random ports and the new doc               |
| `CLAUDE.md`             | Local services list, JDBC/DynamoDB connection notes, project tree comment                                                 |

No Java, POM or `application*.yml` changes.

## Services

| Service    | Image                                         | Host port               | Credentials / settings                                                                                                                                        |
|------------|-----------------------------------------------|-------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `mysql`    | `mysql:9.7.4` (9.7 is the current LTS series) | random (container 3306) | `MYSQL_ROOT_PASSWORD=root`, `MYSQL_DATABASE=fsweb`, `MYSQL_USER=fsweb`, `MYSQL_PASSWORD=fsweb`; volume `mysql-data` on `/var/lib/mysql`                       |
| `postgres` | `postgres:18.6-alpine`                        | random (container 5432) | `POSTGRES_USER=fsweb`, `POSTGRES_PASSWORD=fsweb`, `POSTGRES_DB=fsweb`; volume `postgres-data` on `/var/lib/postgresql` (the 18 image keeps `PGDATA` under it) |
| `dynamodb` | `amazon/dynamodb-local:3.3.2`                 | fixed `8000:8000`       | `-jar DynamoDBLocal.jar -sharedDb -inMemory`; no credentials (any dummy key/secret is accepted)                                                               |

Credentials are development-only. Production settings belong in each backend's `application-prod.yml`, as for Redis and OTLP.

## How Spring Boot connects

### MySQL and PostgreSQL (automatic)

`spring-boot-docker-compose` recognizes the `mysql` and `postgres` images and creates a `JdbcConnectionDetails` bean (and the R2DBC equivalent) from the container's environment and mapped port, so a backend only needs a DataSource and the driver:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>   <!-- or spring-boot-starter-jdbc -->
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>                        <!-- or com.mysql:mysql-connector-j -->
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

| Service    | Variables Boot reads                                                                            | Boot requirement                                      |
|------------|-------------------------------------------------------------------------------------------------|-------------------------------------------------------|
| `mysql`    | `MYSQL_DATABASE`, `MYSQL_USER`/`MYSQL_PASSWORD` (falls back to `root`/`MYSQL_ROOT_PASSWORD`)    | `MYSQL_DATABASE` must be set, otherwise startup fails |
| `postgres` | `POSTGRES_USER` (default `postgres`), `POSTGRES_PASSWORD`, `POSTGRES_DB` (defaults to the user) | `POSTGRES_PASSWORD` must be set                       |

**Two JDBC services, one DataSource.** Boot registers one `JdbcConnectionDetails` per matching container. A backend with a DataSource therefore gets two candidates and fails to start (no unique `JdbcConnectionDetails` bean). Pick one per backend. Options:

1. Label the service the backend does not use so Boot skips it. The label applies to every backend that reads this file, so this only works while all backends agree on one database:

   ```yaml
   mysql:
     labels:
       org.springframework.boot.ignore: true
   ```

2. If backends need different databases, give the file a Compose profile per database (`profiles: ["mysql"]`, `profiles: ["postgres"]`) and set `spring.docker.compose.profiles.active` in each backend's `application.yml`. Services without a profile (`valkey`, `lgtm`, `kafka`, `dynamodb`) start regardless.

Random host ports follow the existing convention (Boot discovers them). A fixed port (`"3306:3306"`, `"5432:5432"`) is easier for a desktop DB client but clashes with a MySQL/PostgreSQL install on the host.

### DynamoDB Local (manual)

Boot has no Docker Compose service connection for DynamoDB, so the host port is fixed at 8000 and the backend configures the endpoint. With Spring Cloud AWS (`io.awspring.cloud:spring-cloud-aws-starter-dynamodb`):

```yaml
spring:
  cloud:
    aws:
      region:
        static: us-west-2
      credentials:
        access-key: dummy
        secret-key: dummy
      dynamodb:
        endpoint: http://localhost:8000
```

With the plain AWS SDK v2, build the client with `endpointOverride(URI.create("http://localhost:8000"))`, a static region and `StaticCredentialsProvider` with any key/secret. Put these under a dev profile; production uses the real region and IAM credentials.

`-sharedDb` makes DynamoDB Local keep one database regardless of the region and access key the client sends (without it, each access-key/region pair sees its own tables). `-inMemory` keeps that database in memory: tables survive app restarts because the container keeps running (`lifecycle-management: start-only`), but not `docker compose down`. For data that survives `down`, replace `-inMemory` with `-dbPath ./data` and mount a volume on `/home/dynamodblocal/data`.

## Verification checklist

| Check                                                                              | Expected result                                                                                                                                                                                                             |
|------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `docker compose -f backends/compose.yaml config`                                   | Valid file; services `valkey`, `lgtm`, `kafka`, `mysql`, `postgres`, `dynamodb`; volumes `mysql-data`, `postgres-data`                                                                                                      |
| `./mvnw clean install`                                                             | Build and tests pass (nothing on the Java side changed; Docker Compose support is skipped in tests)                                                                                                                         |
| `./mvnw spring-boot:run -pl backends/facade`                                       | Log shows compose starting the six services, then the app starts on 8080. Boot waits for TCP readiness on the mapped ports; MySQL and PostgreSQL only open their port after initialization, so the first start takes longer |
| `docker compose -p fs-web-springboot port mysql 3306`                              | Prints `0.0.0.0:<port>`; `mysql -h 127.0.0.1 -P <port> -u fsweb -pfsweb fsweb` connects                                                                                                                                     |
| `docker compose -p fs-web-springboot port postgres 5432`                           | Prints `0.0.0.0:<port>`; `psql -h 127.0.0.1 -p <port> -U fsweb fsweb` connects (password `fsweb`)                                                                                                                           |
| `aws dynamodb list-tables --endpoint-url http://localhost:8000 --region us-west-2` | Returns `{"TableNames": []}` (set any `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` first)                                                                                                                                    |
| `docker compose -p fs-web-springboot down`                                         | Stops and removes the containers; `mysql-data`/`postgres-data` volumes remain unless `-v` is added                                                                                                                          |
