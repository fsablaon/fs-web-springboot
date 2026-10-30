# Dependency Update — 2026-10-30

Branch: `chore/update-dependencies`

Goal: move every dependency to its latest release, majors included.

## Backend / build (`pom.xml`)

| Item                                 | Before       | After        | Notes                                                       |
|--------------------------------------|--------------|--------------|-------------------------------------------------------------|
| Spring Boot parent                   | 4.1.2        | 4.1.2        | Already latest (released 2026-10-22); no 4.2 GA yet         |
| `opentelemetry-logback-appender-1.0` | 2.33.1-alpha | 2.33.1-alpha | Already latest                                              |
| `frontend-maven-plugin`              | 1.15.1       | 2.0.2        | Major. 2.0 raises minimum dependency versions; no config changes |
| Node.js (plugin download)            | v24.12.0     | v26.13.0     | Node 26 is now the Active LTS line                          |
| npm (plugin download)                | 11.7.0       | 12.2.1       | Major. Requires Node `>=26` / `^24.15` / `^22.22.2`         |
| Java                                 | 25           | 25           | Kept on the current LTS                                     |

All other Java dependencies (web, actuator, data-redis, opentelemetry starter, docker-compose, Lombok, test) are managed by the Boot parent.

## Frontend (`frontend/package.json`)

| Package                       | Before   | After    | Type  |
|-------------------------------|----------|----------|-------|
| `react`                       | ^19.2.0  | ^19.3.1  | Minor |
| `react-dom`                   | ^19.2.0  | ^19.3.1  | Minor |
| `@types/react`                | ^19.2.5  | ^19.3.0  | Minor |
| `@types/react-dom`            | ^19.2.3  | ^19.3.0  | Minor |
| `eslint-plugin-react-hooks`   | ^7.0.1   | ^7.1.1   | Minor |
| `typescript-eslint`           | ^8.46.4  | ^8.72.1  | Minor |
| `eslint`                      | ^9.39.1  | ^10.14.0 | Major |
| `@eslint/js`                  | ^9.39.1  | ^10.0.1  | Major |
| `globals`                     | ^16.5.0  | ^17.13.0 | Major |
| `vite`                        | ^7.2.4   | ^8.3.5   | Major |
| `@vitejs/plugin-react`        | ^5.1.1   | ^6.1.2   | Major |
| `@types/node`                 | ^24.10.1 | ^26.6.6  | Major (matches Node 26) |
| `eslint-plugin-react-refresh` | ^0.4.24  | ^0.5.7   | 0.x   |
| `typescript`                  | ~5.9.3   | ~6.0.3   | Major |

`package-lock.json` was regenerated; the old lockfile pinned ESLint 9 internals and blocked the install.

### Held back

| Package      | Latest | Used  | Reason                                                                 |
|--------------|--------|-------|------------------------------------------------------------------------|
| `typescript` | 7.0.2  | 6.0.3 | `typescript-eslint` 8.72.1 (latest) supports TypeScript `<6.1.0` only |

No source or config changes were needed for the frontend majors.

## Docker Compose (`compose.yaml`)

| Service | Before                     | After                      |
|---------|----------------------------|----------------------------|
| `redis` | `redis:8.8-alpine`         | `redis:8.10-alpine`        |
| `lgtm`  | `grafana/otel-lgtm:0.32.0` | `grafana/otel-lgtm:0.32.1` |

## Docs

- `README.md`: Spring Boot 4.0.0 → 4.1.2 (was stale), Node 24+/npm 11+ → Node 26+/npm 12+, image tags.
- `docs/docker-compose-observability.md`: image tags.

## Verification

| Check                                | Result                                                            |
|--------------------------------------|-------------------------------------------------------------------|
| `npm install`                        | OK, 0 vulnerabilities                                             |
| `npm run lint` (ESLint 10)           | OK, no findings                                                   |
| `npm run build` (tsc 6 + Vite 8)     | OK                                                                |
| `mvn clean install` / `mvn test`     | Not run: no Maven Central access and no JDK 25 in the build environment |

Before merging, run `mvn clean install` locally on JDK 25. It exercises frontend-maven-plugin 2.0.2 downloading Node 26.13.0 / npm 12.2.1, and runs the backend tests.
