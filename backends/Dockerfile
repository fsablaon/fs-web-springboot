# syntax=docker/dockerfile:1
# Builds the backend images (facade, visitor, stocks) from ONE Maven build.
#
# The build context is the project root (the Dockerfile needs pom.xml, frontend/ and backends/),
# and each backend is a named target:
#   docker build -f backends/Dockerfile --target facade -t fs-web/facade:local .
# Normally the images are built and started through backends/compose.yaml (Compose profile "apps"):
#   docker compose -f backends/compose.yaml --profile apps up --build -d
# Design in docs/015-backends-in-containers.md.

# ---------------------------------------------------------------------------------------------
# 1. Build: whole Maven reactor (frontend jar + the three backend JARs), tests skipped (they run
#    in ./mvnw clean install on the developer machine / CI, not in the image build).
#    Ubuntu (glibc) on purpose: frontend-maven-plugin downloads the glibc build of Node, which
#    does not run on Alpine (musl). Maven runs on this JDK 25, so the toolchains plugin selects it.
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace
# .dockerignore keeps target/, node_modules/, .git, .worktrees, .vm, ... out of the context
COPY . .
# Cache mounts survive between builds (Maven local repo + wrapper distribution, npm cache).
# sharing=locked: the three targets may build in parallel and Maven's local repo is not safe
# for concurrent writers from separate processes.
RUN --mount=type=cache,id=fs-web-m2,target=/root/.m2,sharing=locked \
    --mount=type=cache,id=fs-web-npm,target=/root/.npm,sharing=locked \
    ./mvnw -B -ntp -DskipTests package

# ---------------------------------------------------------------------------------------------
# 2. Extract: split each executable JAR into layers (dependencies / spring-boot-loader /
#    snapshot-dependencies / application) so a code change only rebuilds the last, small layer.
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS extract-facade
WORKDIR /extract
COPY --from=build /workspace/backends/facade/target/facade-*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-alpine AS extract-visitor
WORKDIR /extract
COPY --from=build /workspace/backends/visitor/target/visitor-*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-alpine AS extract-stocks
WORKDIR /extract
COPY --from=build /workspace/backends/stocks/target/stocks-*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---------------------------------------------------------------------------------------------
# 3. Runtime: JRE only, non-root user, the extracted layers copied in order of change frequency.
#    Configuration comes from the environment (SPRING_PROFILES_ACTIVE=prod + the variables
#    application-prod.yml reads), see compose.yaml. JVM flags go in JAVA_TOOL_OPTIONS.
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S app && adduser -S -G app app
WORKDIR /application
USER app
ENTRYPOINT ["java", "-jar", "application.jar"]

FROM runtime AS facade
COPY --from=extract-facade /extract/extracted/dependencies/ ./
COPY --from=extract-facade /extract/extracted/spring-boot-loader/ ./
COPY --from=extract-facade /extract/extracted/snapshot-dependencies/ ./
COPY --from=extract-facade /extract/extracted/application/ ./
EXPOSE 8080

FROM runtime AS visitor
COPY --from=extract-visitor /extract/extracted/dependencies/ ./
COPY --from=extract-visitor /extract/extracted/spring-boot-loader/ ./
COPY --from=extract-visitor /extract/extracted/snapshot-dependencies/ ./
COPY --from=extract-visitor /extract/extracted/application/ ./
EXPOSE 8081

FROM runtime AS stocks
COPY --from=extract-stocks /extract/extracted/dependencies/ ./
COPY --from=extract-stocks /extract/extracted/spring-boot-loader/ ./
COPY --from=extract-stocks /extract/extracted/snapshot-dependencies/ ./
COPY --from=extract-stocks /extract/extracted/application/ ./
EXPOSE 8082
