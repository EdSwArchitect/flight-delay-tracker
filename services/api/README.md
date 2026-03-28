# api

Javalin REST API that serves enriched flight data and delay information from Redis, with virtual thread support and CORS headers.

## What it does

- Serves enriched flight records from Redis `flight:enriched:*` keys via REST endpoints
- Provides single-flight lookup by ICAO24 hex address
- Serves active delay data from Redis `flight:delay:*` keys
- Enables CORS for all origins (local development)
- Runs Javalin with virtual threads for high-concurrency request handling

## Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Health check (returns `{"status":"ok"}`) |
| `GET` | `/flights` | List all enriched flight records |
| `GET` | `/flights/{icao24}` | Get a single enriched flight by ICAO24 hex (404 if not found) |
| `GET` | `/delays` | List all active flight delays |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `HTTP_PORT` | `8084` | Port for the REST API |
| `METRICS_PORT` | `8085` | Port for the Prometheus `/metrics` endpoint |

## Ports

| Port | Purpose |
|---|---|
| 8084 | REST API |
| 8085 | Prometheus metrics (`/metrics`) |

## Dependencies

- **Redis** -- reads `flight:enriched:*` and `flight:delay:*` keys (Lettuce client)

## Key files

| File | Description |
|---|---|
| `src/main/java/.../api/FlightApiApp.java` | Main class: Javalin server setup, route handlers, CORS filter, request metrics |
| `src/main/resources/application.properties` | Default configuration values |
| `src/main/resources/logback.xml` | Logging configuration |
| `Dockerfile` | Multi-stage Docker image (eclipse-temurin:21-jre-alpine) |
| `pom.xml` | Maven module descriptor |

## Metrics

| Metric | Type | Description |
|---|---|---|
| `http.requests.total{method,path,status}` | Counter | Request count by HTTP method, matched path, and response status |
| `http.request.duration.seconds{method,path}` | Histogram | Request duration by HTTP method and matched path |

## Build and run locally

```bash
mvn clean package -DskipTests -pl services/api
java -jar services/api/target/prototype.flightdelays.api-0.1.0-SNAPSHOT.jar
```

## Docker

```bash
docker build -t flight-tracker/api services/api
```
