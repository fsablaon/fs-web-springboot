# fs-web-springboot

A full-stack web application with Spring Boot backend and React frontend.

## Technologies

**Backend:**
- Java 25
- Spring Boot 4.0.0
- Spring Framework 7.0
- Spring Web
- Lombok
- Maven

**Frontend:**
- React 19
- TypeScript
- Vite
- CSS Modules

## Prerequisites

- JDK 25 or later
- Maven 3.6+
- Node.js 24+ and npm 11+ (for frontend development)

## Getting Started

### Development Mode (Recommended)

For development with hot reload:

1. **Start the backend** (Terminal 1):
```bash
mvn spring-boot:run
```
The backend will run on `http://localhost:8080`

2. **Start the frontend** (Terminal 2):
```bash
cd frontend
npm install  # Only needed first time
npm run dev
```
The frontend will run on `http://localhost:3000`

3. **Access the application** at `http://localhost:3000`

The Vite dev server will proxy API requests to the Spring Boot backend, giving you hot reload for both frontend and backend changes.

### Production Build

Build and run the complete application as a single JAR:

```bash
mvn clean install
java -jar target/demo-0.0.1-SNAPSHOT.jar
```

Access the application at `http://localhost:8080`

The Maven build automatically:
1. Installs Node.js and npm (via frontend-maven-plugin)
2. Installs frontend dependencies
3. Builds the React app
4. Bundles it into the Spring Boot JAR

## Project Structure

```
fs-web-springboot/
├── src/main/java/          # Spring Boot backend code
├── src/main/resources/     # Backend configuration
├── frontend/               # React frontend code
│   ├── src/               # React components and TypeScript
│   └── vite.config.ts     # Vite configuration with proxy
└── pom.xml                # Maven configuration
```

## API Endpoints

- `GET /api/hello?name=YourName` - Returns a greeting message

## Running Tests

### Backend Tests
```bash
mvn test
```

### Frontend Tests
```bash
cd frontend
npm run lint
```

## Additional Commands

### Backend Only
```bash
mvn spring-boot:run      # Run backend
mvn clean               # Clean build artifacts
mvn package             # Package as JAR
```

### Frontend Only
```bash
cd frontend
npm run dev             # Start dev server
npm run build           # Build for production
npm run preview         # Preview production build
npm run lint            # Run ESLint
```
