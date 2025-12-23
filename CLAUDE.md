# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a full-stack web application with Spring Boot backend and React frontend.

**Backend:**
- **Base Package**: `org.fsweb.demo`
- **Java Version**: 25
- **Spring Boot Version**: 4.0.0
- **Spring Framework Version**: 7.0
- **Main Application Class**: `FsWebApplication`
- **Configuration**: Uses `application.yml` for configuration

**Frontend:**
- **Framework**: React 19 with TypeScript
- **Build Tool**: Vite
- **Styling**: CSS Modules
- **Dev Server Port**: 3000
- **Location**: `frontend/` directory

## Build & Development Commands

### Full-Stack Development (Recommended)
For development with hot reload:
1. **Start backend**: `mvn spring-boot:run` (runs on port 8080)
2. **Start frontend** (in separate terminal): `cd frontend && npm run dev` (runs on port 3000)
3. Access the app at `http://localhost:3000` (Vite proxies API calls to port 8080)

### Production Build
- **Full build**: `mvn clean install` (builds both backend and frontend, bundles React into Spring Boot JAR)
- **Run production build**: `mvn spring-boot:run` or `java -jar target/demo-0.0.1-SNAPSHOT.jar`
- Access at `http://localhost:8080`

### Backend Only (Maven)
- **Build**: `mvn clean install`
- **Run application**: `mvn spring-boot:run`
- **Run tests**: `mvn test`
- **Run single test**: `mvn test -Dtest=ClassName#methodName`
- **Package**: `mvn package`
- **Clean**: `mvn clean`

### Frontend Only (npm)
All commands run from `frontend/` directory:
- **Install dependencies**: `npm install`
- **Start dev server**: `npm run dev`
- **Build for production**: `npm run build` (outputs to `src/main/resources/static/`)
- **Lint**: `npm run lint`
- **Preview production build**: `npm run preview`

## Project Structure

```
fs-web-springboot/
├── src/
│   ├── main/
│   │   ├── java/org/fsweb/demo/
│   │   │   ├── FsWebApplication.java    # Main Spring Boot application
│   │   │   ├── config/                  # Configuration classes
│   │   │   │   └── WebConfig.java       # SPA routing configuration
│   │   │   └── controller/              # REST API controllers
│   │   │       └── HelloController.java
│   │   └── resources/
│   │       ├── application.yml          # Backend configuration
│   │       └── static/                  # Built React app (generated)
│   └── test/
│       └── java/org/fsweb/demo/
│           └── FsWebApplicationTests.java
├── frontend/
│   ├── src/
│   │   ├── App.tsx                      # Main React component
│   │   ├── App.module.css               # Component styles
│   │   ├── main.tsx                     # React entry point
│   │   └── index.css                    # Global styles
│   ├── vite.config.ts                   # Vite configuration (proxy setup)
│   ├── tsconfig.json                    # TypeScript configuration
│   └── package.json                     # Frontend dependencies
└── pom.xml                              # Maven configuration
```

## Architecture Guidelines

### Typical Spring Boot Layered Architecture
- **Controllers** (`@RestController`/`@Controller`): Handle HTTP requests, map to service methods
- **Services** (`@Service`): Business logic layer, orchestrates operations
- **Repositories** (`@Repository`): Data access layer, interfaces extending JpaRepository/CrudRepository
- **Models/Entities** (`@Entity`): JPA entities representing database tables
- **DTOs**: Data transfer objects for API requests/responses
- **Configuration** (`@Configuration`): Spring configuration classes

### Spring Boot Conventions
- Application entry point: `FsWebApplication` class with `@SpringBootApplication` and `main()` method
- Configuration: `application.yml` in `src/main/resources/`
- Use dependency injection via constructor injection (preferred) or `@Autowired`
- Exception handling: Use `@ControllerAdvice` for global exception handling
- Lombok is enabled: Use annotations like `@Slf4j`, `@Data`, `@Builder` to reduce boilerplate

## Frontend-Backend Integration

### Development Mode
- Frontend runs on Vite dev server (port 3000) with hot reload
- Backend runs on Spring Boot (port 8080)
- Vite proxy configuration in `frontend/vite.config.ts` forwards `/api/*` requests to backend
- CORS is handled by the proxy, no additional backend configuration needed

### Production Mode
- Frontend is built and bundled into `src/main/resources/static/`
- Spring Boot serves the React app as static files
- `WebConfig.java` handles SPA routing: non-API routes fall back to `index.html`
- Single JAR deployment contains both frontend and backend

### API Conventions
- All backend API endpoints are prefixed with `/api`
- Frontend makes relative fetch calls (e.g., `fetch('/api/hello')`)
- This works in both dev (proxied) and production (same origin)
