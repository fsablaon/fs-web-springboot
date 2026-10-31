# Keycloak OAuth: local IdP container, facade as resource server, login in the UI — 2026-10-30

Branch: `feature/keycloak-oauth`, worktree `.worktrees/keycloak-oauth` (created from `main` after PR #29; rebased onto `main` after PR #31, Kafka visit events, `docs/011-kafka-visit-events.md`, after PR #33, JDK 25 toolchain, `docs/012-jdk25-toolchain.md`, and after PR #34, facade HTTP clients and `GET /api/overview`, `docs/013-facade-http-clients.md`)

Status: implemented on `feature/keycloak-oauth`.

Goal: add a local Keycloak container to `backends/compose.yaml`, make the facade backend require a valid access token on `/api/**`, and give the React UI a sign-in / sign-out flow so `/api/hello` is called as a logged-in user. The visitor and stocks backends are unchanged in this step (see Follow-ups).

## Decision: where the OAuth flow runs

| Option                                                                        | How it works                                                                                                                      | Verdict                                                                                                                                                                   |
|-------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **A. SPA does Authorization Code + PKCE, facade is an OAuth2 resource server** | Browser redirects to Keycloak, gets tokens, sends `Authorization: Bearer <access token>` to `/api/*`; facade validates the JWT   | **Chosen.** Facade stays stateless; works unchanged through the Vite proxy and the packaged JAR; the same token can later be sent to visitor/stocks directly               |
| B. Facade as BFF (`spring-boot-starter-oauth2-client`, session cookie)        | Facade does the redirect flow, keeps tokens server-side, UI uses a session cookie                                                 | Not now. Needs extra Vite proxy entries for `/oauth2/**` and `/login/**`, and `/api/visits` + `/api/stocks` (called directly by the UI) would need a token relay from facade |
| C. Spring Authorization Server instead of Keycloak                            | A fourth Spring Boot backend issues tokens                                                                                        | Not now. The request is for Keycloak; Keycloak gives an admin console, realm import and user management for free                                                         |

Option A trade-off: tokens live in the browser (`sessionStorage`). Acceptable for a dev/demo app; option B is the upgrade path if that becomes a concern.

## Flow

1. UI loads, calls `GET /api/auth/config` (public) → `{ "issuerUri": "http://localhost:8180/realms/fsweb", "clientId": "fs-web-ui" }`.
2. UI shows a **Sign in** button. Click → `oidc-client-ts` redirects to Keycloak's authorization endpoint (PKCE S256, `scope=openid profile email`).
3. User logs in (`france` / `password`). Keycloak redirects back to `http://localhost:3000/?code=…&state=…` (or `:8080` for the packaged JAR).
4. `oidc-client-ts` exchanges the code at Keycloak's token endpoint (browser → `localhost:8180`, cross-origin, allowed by the client's Web Origins), stores the tokens, and the UI strips `code`/`state` from the URL.
5. UI calls `GET /api/hello` and `GET /api/me` with `Authorization: Bearer <access token>`.
6. Facade validates the JWT (signature via JWKS, `iss`, `exp`, `aud` = `facade`), maps Keycloak realm roles to `ROLE_*`, publishes the visit event to Kafka through `VisitEventPublisher` as before, and answers. No token → `401`.
7. **Sign out** → Keycloak end-session endpoint → back to the app, unauthenticated.

`iss` must be the same string in three places: the token Keycloak issues, `spring.security.oauth2.resourceserver.jwt.issuer-uri` in facade, and `authority` in the UI. In dev all three are `http://localhost:8180/realms/fsweb`; the browser and the facade both reach Keycloak on the host port.

## Keycloak container (`backends/compose.yaml`)

```yaml
  keycloak:
    # OpenID Connect provider for the UI and the backends. Spring Boot has no Docker Compose
    # service connection for Keycloak, so the host port is fixed (like dynamodb) and the issuer
    # URI is configured explicitly in application.yml. Dev mode (start-dev): HTTP, no hostname
    # checks, embedded H2 (data survives restarts of the container, not `compose down`).
    # Admin console: http://localhost:8180 (admin / admin). Realm "fsweb" is imported from
    # backends/keycloak/fsweb-realm.json on first start (--import-realm skips existing realms).
    image: quay.io/keycloak/keycloak:26.8.1
    command: ["start-dev", "--import-realm"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: admin
      KC_HEALTH_ENABLED: "true"
    ports:
      - "8180:8080"   # fixed host port: http://localhost:8180 (8080 is the facade)
    volumes:
      - ./keycloak:/opt/keycloak/data/import:ro
```

| Item                                 | Value / reason                                                                                                                              |
|--------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| Image                                | `quay.io/keycloak/keycloak:26.8.1` (latest stable at time of writing)                                                                       |
| Host port                            | `8180` — fixed on purpose; tokens carry `iss` with this host/port and the UI needs a stable URL                                             |
| Admin                                | `admin` / `admin` (dev only; `KC_BOOTSTRAP_ADMIN_*` is the Keycloak 26 name, `KEYCLOAK_ADMIN` is deprecated)                               |
| Realm import                         | `backends/keycloak/fsweb-realm.json` mounted at `/opt/keycloak/data/import`; relative path is resolved against the compose file's directory |
| Startup                              | 15–30 s. `spring-boot-docker-compose` waits for the mapped TCP port (default timeout 2 min), so the first `spring-boot:run` is slower       |
| Shared by all backends               | Like every service in this file, `keycloak` starts for `visitor` and `stocks` too, even though only `facade` uses it in this step          |
| Stop                                 | `docker compose -p fs-web-springboot down` (unchanged); re-creating the container re-imports the realm                                      |

## Realm `fsweb` (`backends/keycloak/fsweb-realm.json`)

Hand-written import file (smaller than a full export; Keycloak adds the default client scopes, flows and keys on import). To change the realm, edit it in the admin console and export it (Realm settings → Action → Partial export), or edit the JSON and re-create the container (`--import-realm` skips a realm that already exists).

| Setting                                  | Value                                                                                                               |
|------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| Realm                                    | `fsweb`, enabled                                                                                                    |
| Client                                   | `fs-web-ui`, OpenID Connect, **public** (no secret), Standard flow on, PKCE method `S256`, Implicit flow off        |
| Direct access grants                     | **On**, dev only — lets `curl` get a token with the password grant for API testing (see Verification)              |
| Valid redirect URIs                      | `http://localhost:3000/*`, `http://localhost:8080/*`                                                                |
| Valid post logout redirect URIs          | `+` (same as redirect URIs)                                                                                         |
| Web origins                              | `http://localhost:3000`, `http://localhost:8080` (CORS for the browser → token endpoint call)                        |
| Realm roles                              | `user`, `admin`                                                                                                     |
| Users                                    | `france` / `password` (roles `user`, `admin`), `alice` / `password` (role `user`); email verified, no required actions |
| Access token lifespan                    | 5 min (default); `oidc-client-ts` renews silently with the refresh token                                            |
| Audience                                 | Protocol mapper `facade-audience` (`oidc-audience-mapper`) adds `aud: facade` to access tokens; facade rejects tokens without it (`spring.security.oauth2.resourceserver.jwt.audiences: facade`) |
| Claims                                   | Keycloak defaults: `preferred_username`, `name`, `email`, `realm_access.roles`, `azp: fs-web-ui`                     |

Users in the export file look like this (plain-text passwords are allowed in import files):

```json
"users": [
  { "username": "france", "enabled": true, "emailVerified": true,
    "firstName": "France", "email": "france@example.com",
    "credentials": [{ "type": "password", "value": "password", "temporary": false }],
    "realmRoles": ["user", "admin"] }
]
```

## Facade changes (`backends/facade`)

### Dependencies (`backends/facade/pom.xml`)

| Dependency                                  | Scope | Why                                                                     |
|---------------------------------------------|-------|-------------------------------------------------------------------------|
| `spring-boot-starter-security-oauth2-resource-server`      | main  | Spring Security + JWT validation (`NimbusJwtDecoder`) from `issuer-uri` (Boot 4 name; `spring-boot-starter-oauth2-resource-server` is the deprecated alias) |
| `spring-boot-starter-security-oauth2-resource-server-test` | test  | `spring-security-test`: `jwt()` request post-processor                  |
| `spring-boot-starter-webmvc-test`                          | test  | `@AutoConfigureMockMvc` (moved to its own module in Boot 4)             |

### New and changed files

```
backends/facade/src/main/java/org/fsweb/facade/
├── config/
│   ├── SecurityConfig.java            # SecurityFilterChain: stateless, /api/** needs a JWT
│   └── AuthProperties.java            # facade.auth.* (issuer-uri, client-id) → /api/auth/config
├── security/
│   └── KeycloakRealmRoleConverter.java  # realm_access.roles → ROLE_<role>
├── controller/
│   ├── HelloController.java           # name defaults to the token's preferred_username
│   ├── AuthConfigController.java      # GET /api/auth/config (public)
│   └── MeController.java              # GET /api/me (authenticated)
└── dto/
    ├── AuthConfig.java                # record (issuerUri, clientId)
    └── UserInfo.java                  # record (username, name, email, roles)
```

### `SecurityConfig`

| Rule                                                       | Access                                               |
|------------------------------------------------------------|------------------------------------------------------|
| `GET /api/auth/config`                                     | `permitAll` — the UI needs it before it can log in   |
| `/api/**` (everything else under `/api`)                   | `authenticated` (valid bearer JWT)                   |
| `/actuator/health`, `/actuator/info`                       | `permitAll`                                          |
| `/actuator/**` (other)                                     | `authenticated`                                      |
| everything else (`/`, `/assets/**`, SPA routes)            | `permitAll` — static files served by `WebConfig`     |

- `oauth2ResourceServer(o -> o.jwt(...))` with a `JwtAuthenticationConverter` that reads `realm_access.roles` and maps each to `ROLE_<role>` (Spring's default only reads `scope`/`scp`). No endpoint uses roles yet; the mapping is there so `@PreAuthorize("hasRole('admin')")` works when needed.
- `csrf.disable()` and `SessionCreationPolicy.STATELESS` — bearer tokens, no cookies.
- Unauthenticated `/api/**` → `401` with `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource"` (Spring Security 7 adds the RFC 9728 pointer); invalid/expired token or wrong `aud`/`iss` → `401` with `error="invalid_token"` and the reason in `error_description`. No redirect to a login page (that is the UI's job).
- Spring Security 7 (Boot 4.1): lambda DSL only; `requestMatchers("/api/**")` uses `PathPattern` matching.

### Endpoints after this change

| Endpoint               | Auth   | Response                                                                                                        |
|------------------------|--------|-----------------------------------------------------------------------------------------------------------------|
| `GET /api/auth/config` | none   | `{"issuerUri":"http://localhost:8180/realms/fsweb","clientId":"fs-web-ui"}`                                      |
| `GET /api/hello`       | bearer | `Hello, <name>!` — `?name=` if given, else `preferred_username` from the token; still records a visit            |
| `GET /api/me`          | bearer | `{"username":"france","name":"France","email":"france@example.com","roles":["user","admin"]}`                     |

`HelloController.hello(...)` gains an `@AuthenticationPrincipal Jwt jwt` parameter. The existing plain-object `HelloControllerTests` keep working by passing a `Jwt` built with `Jwt.withTokenValue(...)`.

### Configuration

`application.yml`:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          # Keycloak realm (fixed host port, see backends/compose.yaml). Boot resolves
          # <issuer-uri>/.well-known/openid-configuration lazily on the first request,
          # so the app (and the tests) start without Keycloak running.
          issuer-uri: http://localhost:8180/realms/fsweb
          audiences: facade

facade:
  auth:
    # Returned by GET /api/auth/config so the UI is not built with a hard-coded issuer
    issuer-uri: ${spring.security.oauth2.resourceserver.jwt.issuer-uri}
    client-id: fs-web-ui
```

`application-prod.yml`:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/fsweb}

facade:
  auth:
    issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/fsweb}
    client-id: ${OIDC_CLIENT_ID:fs-web-ui}
```

In production the browser and the facade may reach Keycloak by different hostnames; `iss` is what the browser used. Keycloak must then run with `KC_HOSTNAME` set to the public URL and the facade's `issuer-uri` set to the same string (with `jwk-set-uri` pointing at the internal address if needed). Out of scope here.

### Visit events (facade → Kafka → visitor)

Unchanged: `/api/hello` still publishes one `VisitEvent` to `fs-web.visits` (`docs/011-kafka-visit-events.md`). Only signed-in requests reach `/api/hello` now, so every event comes from an authenticated call, but the event does not carry the user (no `sub`/username field). Adding one is a change to both `VisitEvent` records and is left for later.

### `GET /api/overview` and the facade's HTTP clients (PR #34)

`/api/overview` is under `/api/**`, so it now needs a bearer token like `/api/hello` (`SecurityConfigTests.overviewWithoutTokenIsUnauthorized`). Its HTTP service clients (`visitor` and `stocks` groups, `docs/013-facade-http-clients.md`) still call `GET /api/visits` and `GET /api/stocks` **without** a token, which works because those backends are not protected yet. The UI does not call `/api/overview` yet.

## Frontend changes (`frontend/`)

### Dependencies (`package.json`)

| Package              | Version | Why                                                                                  |
|----------------------|---------|--------------------------------------------------------------------------------------|
| `oidc-client-ts`     | ^3.5.0  | Provider-neutral OIDC client: PKCE, token storage, silent renew, logout              |
| `react-oidc-context` | ^3.3.1  | `AuthProvider` + `useAuth()` hook over `oidc-client-ts`                              |

`keycloak-js` was considered; it is simpler but Keycloak-specific. `oidc-client-ts` keeps the UI independent of the IdP.

### Files

```
frontend/src/
├── main.tsx                 # fetch /api/auth/config, then render <AuthProvider …><App/></AuthProvider>
├── App.tsx                  # Sign in / Sign out, shows username, sends Bearer token on /api/hello and /api/me
├── App.module.css           # styles for the auth bar
└── auth/
    ├── authConfig.ts        # builds the oidc-client-ts UserManagerSettings from /api/auth/config
    └── apiFetch.ts          # fetch wrapper adding Authorization: Bearer <access_token>
```

`authConfig.ts`:

```ts
import { WebStorageStateStore } from 'oidc-client-ts'

export async function loadAuthConfig() {
  const res = await fetch('/api/auth/config')
  if (!res.ok) throw new Error('Cannot load auth config')
  const { issuerUri, clientId } = await res.json() as { issuerUri: string; clientId: string }
  return {
    authority: issuerUri,
    client_id: clientId,
    redirect_uri: window.location.origin + '/',
    post_logout_redirect_uri: window.location.origin + '/',
    scope: 'openid profile email',
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    // remove ?code=&state= after the redirect back from Keycloak
    onSigninCallback: () => window.history.replaceState({}, document.title, window.location.pathname),
  }
}
```

`App.tsx` behaviour:

| State                       | UI                                                                                                      |
|-----------------------------|---------------------------------------------------------------------------------------------------------|
| `auth.isLoading`            | "Checking sign-in…"                                                                                      |
| not authenticated           | title, visit counter (unchanged, `/api/visits` is still public), **Sign in** button; greeting form hidden |
| authenticated               | "Signed in as `preferred_username`" + **Sign out**; greeting form; name input placeholder = username      |
| `auth.error`                | error line (e.g. Keycloak not running)                                                                   |

- `fetchGreeting` uses `apiFetch(url, auth.user.access_token)`; a `401` sets the error "Session expired, sign in again" and calls `auth.removeUser()`.
- The page-load `POST /api/visits` stays as is (visitor backend has no auth in this step).
- No Vite proxy changes: the UI talks to Keycloak directly (`localhost:8180`), the API calls still go through the existing `/api` proxy entries.
- `index.css`/`App.module.css`: a small top bar for the user name and sign-in/out buttons.

## Tests

| Module   | Test                                              | Checks                                                                                                   |
|----------|---------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| facade   | `FacadeApplicationTests`                          | Context loads without Keycloak (lazy `JwtDecoder`)                                                       |
| facade   | `HelloControllerTests` (unit, existing)           | Updated for the `Jwt` parameter; still verifies one `VisitEvent` per greeting (mock `KafkaTemplate`)    |
| facade   | `SecurityConfigTests` (`@SpringBootTest` + `MockMvc`) | `/api/hello` → 401 without token; 200 with `.with(jwt().jwt(j -> j.claim("preferred_username","france")))`; `/api/auth/config` → 200 without token; `/api/me` returns roles mapped from `realm_access.roles`; `/actuator/health` public |
| frontend | `npm run lint`, `npm run build`                   | Type-checks the new auth code (no unit test framework in the project yet)                               |

The `@SpringBootTest` test mocks the `JwtDecoder` bean (`@MockitoBean`) so no network call to the issuer happens.

## Verification (dev)

1. `./mvnw clean install` (full build, tests included).
2. `scripts/dev.sh` (or `./mvnw spring-boot:run -pl backends/facade` etc.). First start pulls the Keycloak image and waits for it.
3. `http://localhost:8180` → admin console, `admin`/`admin`, realm `fsweb` exists with client `fs-web-ui` and users `france`, `alice`.
4. `http://localhost:3000` → **Sign in** → `france`/`password` → "Signed in as france", greeting works, visit counter increments; **Sign out** returns to the signed-out view.
5. `curl -i http://localhost:8080/api/hello` → `401`.
6. Token via password grant (direct access grants, dev only), then the API:

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/realms/fsweb/protocol/openid-connect/token \
  -d client_id=fs-web-ui -d grant_type=password -d username=france -d password=password \
  | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/hello   # Hello, france!
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/me
```

7. Packaged JAR: `java -jar backends/facade/target/facade-0.0.1-SNAPSHOT.jar` with Keycloak still running → `http://localhost:8080` login works (redirect URI `http://localhost:8080/*` is registered).

## Verification results (2026-10-30)

| Check                                                                                    | Result                                                                                       |
|------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| `./mvnw clean install` (JDK 25.0.5, Maven 3.9.16 via wrapper)                            | BUILD SUCCESS (re-run after the rebase onto PR #34); facade 26 tests (Hello 5, Overview 4, HttpServiceClientsConfig 2, SecurityConfig 10, role converter 3, VisitEventPublisher 1, context 1), visitor 3 and stocks 7 green |
| `npm run lint`, `npm run build`                                                          | clean                                                                                        |
| Packaged facade JAR against a stand-in issuer on `:8180` (discovery + JWKS, RS256 tokens) | see below                                                                                    |

| Request                                      | Status | Body / header                                                               |
|----------------------------------------------|--------|-----------------------------------------------------------------------------|
| `GET /api/auth/config`, no token             | 200    | `{"issuerUri":"http://localhost:8180/realms/fsweb","clientId":"fs-web-ui"}` |
| `GET /api/hello`, no token                   | 401    | `WWW-Authenticate: Bearer resource_metadata=…`                              |
| `GET /api/hello`, valid token                | 200    | `Hello, france!` (about 2 s with no Kafka broker: `max.block.ms`, event logged as not published) |
| `GET /api/hello?name=Bob`, valid token       | 200    | `Hello, Bob!`                                                               |
| `GET /api/me`, valid token                   | 200    | `{"username":"france",…,"roles":["admin","user"]}`                         |
| `GET /api/hello`, `aud` without `facade`     | 401    | `error="invalid_token"`, "The aud claim is not valid"                       |
| `GET /api/hello`, expired token              | 401    | `error="invalid_token"`, "Jwt expired at …"                                 |
| `GET /api/hello`, other issuer               | 401    |                                                                             |
| `GET /api/overview`, no token / valid token  | 401 / 200 | `{"visits":null,"stocks":null}` with visitor and stocks not running (PR #34's fallback) |
| `GET /`, `GET /actuator/health`              | 200    | React `index.html`; `{"status":"UP"}`                                       |
| `GET /actuator/metrics`, no token            | 401    |                                                                             |

Not run in that environment (no Docker): the real Keycloak container, the realm import and the browser sign-in flow (Verification steps 2–4 and 6). Run those locally with `scripts/dev.sh` (or `scripts\dev.cmd`).

## Files changed

| File                                                                 | Change                                                     |
|----------------------------------------------------------------------|------------------------------------------------------------|
| `backends/compose.yaml`                                              | add `keycloak` service                                     |
| `backends/keycloak/fsweb-realm.json`                                 | new: realm export with client, roles, users                |
| `backends/facade/pom.xml`                                            | add security-oauth2-resource-server (+ `-test`), webmvc-test |
| `backends/facade/src/main/java/.../config/SecurityConfig.java`       | new                                                        |
| `backends/facade/src/main/java/.../config/AuthProperties.java`       | new                                                        |
| `backends/facade/src/main/java/.../controller/AuthConfigController.java` | new: `GET /api/auth/config`                            |
| `backends/facade/src/main/java/.../controller/MeController.java`     | new: `GET /api/me`                                         |
| `backends/facade/src/main/java/.../controller/HelloController.java`  | default name from the token                                |
| `backends/facade/src/main/java/.../dto/AuthConfig.java`, `UserInfo.java` | new records                                            |
| `backends/facade/src/main/resources/application.yml`                 | `issuer-uri`, `facade.auth.*`                              |
| `backends/facade/src/main/resources/application-prod.yml`            | `KEYCLOAK_ISSUER_URI`, `OIDC_CLIENT_ID`                    |
| `backends/facade/src/main/java/.../security/KeycloakRealmRoleConverter.java` | new: `realm_access.roles` → `ROLE_*`               |
| `backends/facade/src/test/java/.../config/SecurityConfigTests.java`  | new                                                        |
| `backends/facade/src/test/java/.../security/KeycloakRealmRoleConverterTest.java` | new                                            |
| `backends/facade/src/test/java/.../controller/HelloControllerTests.java` | pass a `Jwt`                                           |
| `frontend/package.json`                                              | add `oidc-client-ts`, `react-oidc-context`                 |
| `frontend/src/main.tsx`, `App.tsx`, `App.module.css`                 | auth provider, sign in/out, bearer token                   |
| `frontend/src/auth/authConfig.ts`, `apiFetch.ts`                     | new                                                        |
| `CLAUDE.md`                                                          | compose service list, facade description, endpoint table, ports, dev steps, docs list |
| `docs/014-keycloak-oauth.md`                                         | this document                                              |

## CLAUDE.md updates

- Local services: add `keycloak` (Keycloak 26, dev mode, host port 8180, admin/admin, realm `fsweb` imported from `backends/keycloak/`).
- Facade: "`/api/**` requires a Keycloak JWT (OAuth2 resource server); `GET /api/auth/config` public; `GET /api/me`".
- Project structure: new `backends/keycloak/`, `config/SecurityConfig.java`, `frontend/src/auth/`.
- Dev steps: mention the Keycloak admin console URL and the test users.
- Design notes: `docs/014-keycloak-oauth.md`.

## Follow-ups (not in this change)

| Item                                                            | Note                                                                                                     |
|-----------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| Protect `visitor` and `stocks`                                  | Same resource-server setup; the UI already has the token. The facade's HTTP service client groups then need a token: relay the caller's token (a `ClientHttpRequestInterceptor` on each group reading the current `JwtAuthenticationToken`) or client credentials (a confidential `facade` client in Keycloak). The Kafka path carries no token; add the user to `VisitEvent` if visitor needs it |
| Role-based endpoints                                            | e.g. `DELETE /api/visits` for `admin` only, using the `ROLE_*` mapping from this change                  |
| Turn off direct access grants                                   | Once API testing uses a proper tool or a confidential test client                                        |
| Keycloak on PostgreSQL                                          | Point `KC_DB=postgres` at the existing `postgres` service for data that survives `compose down`          |
| Production hostname                                             | `KC_HOSTNAME`, HTTPS, matching `issuer-uri`                                                             |
