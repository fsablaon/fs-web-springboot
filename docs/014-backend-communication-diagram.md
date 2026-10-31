# 014 — Backend communication diagram

## Task

Add a visual diagram of how the backend apps communicate, placed in the `backends/` directory, as swim lanes:
UI frontend, facade service, backend services, and external systems.

## Result

| File                                 | Content                                                                       |
|--------------------------------------|-------------------------------------------------------------------------------|
| `backends/backend-communication.svg` | Hand-written swim-lane SVG (four full-height lanes, absolute coordinates)     |
| `backends/backend-communication.md`  | Embeds the SVG; table of every communication path with classes and config keys |

Lanes, left to right:

| Lane                   | Contents                                                                                  |
|------------------------|-------------------------------------------------------------------------------------------|
| UI frontend            | Browser / React UI (Vite proxy per API owner in dev, facade-served UI + reverse proxy in prod) |
| facade service         | `facade` :8080 — `/api/hello`, `/api/overview`, `VisitEventPublisher`, HTTP service clients |
| backend services       | `visitor` :8081 (`/api/visits`, `VisitEventListener`, `VisitCounterService`), `stocks` :8082 (`/api/stocks`, in-memory) |
| external systems       | Kafka `fs-web.visits`, Valkey visit counter, Grafana LGTM (OTLP); note on unused mysql/postgres/dynamodb |

Edges: browser → each owner; facade → Kafka → visitor; facade → visitor and stocks over HTTP for `/api/overview`; visitor → Valkey; all backends → LGTM (dotted).

Mermaid was tried first and dropped: its subgraphs cannot be forced into equal-height columns, so the lanes did not line up. The SVG is edited by hand; there is no generator step.

Everything was taken from the current controllers, `@HttpExchange` interfaces, `application.yml` files and `compose.yaml`; no code changed.
