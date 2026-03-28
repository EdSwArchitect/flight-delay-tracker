# ws-server

WebSocket server that broadcasts enriched flight data snapshots to connected browser clients on a configurable interval.

## What it does

- Accepts WebSocket connections at `/ws/positions`
- Sends a full snapshot of all `flight:enriched:*` records immediately on connect
- Broadcasts updated snapshots to all connected clients every 5 seconds (configurable)
- Tracks active sessions with automatic cleanup on disconnect or error
- Wraps flight data in typed envelopes: `snapshot`, `delta`, and `heartbeat` (with `type`, `timestamp`, and `flights` fields)
- Provides a `/health` endpoint for liveness probes

## Message envelope format

```json
{
  "type": "snapshot",
  "timestamp": "2026-03-28T12:00:00Z",
  "flights": [ ... ]
}
```

## Configuration

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `HTTP_PORT` | `8086` | Port for the WebSocket server |
| `METRICS_PORT` | `8087` | Port for the Prometheus `/metrics` endpoint |
| `BROADCAST_INTERVAL_SEC` | `5` | Seconds between snapshot broadcasts |

## Ports

| Port | Purpose |
|---|---|
| 8086 | WebSocket server (`/ws/positions`) and health check (`/health`) |
| 8087 | Prometheus metrics (`/metrics`) |

## Dependencies

- **Redis** -- reads `flight:enriched:*` keys for snapshot assembly (Lettuce client)

## Key files

| File | Description |
|---|---|
| `src/main/java/.../ws/WsServerApp.java` | Main class: WebSocket handler, broadcast scheduler, snapshot builder, session tracking |
| `src/main/resources/application.properties` | Default configuration values |
| `src/main/resources/logback.xml` | Logging configuration |
| `Dockerfile` | Multi-stage Docker image (eclipse-temurin:21-jre-alpine) |
| `pom.xml` | Maven module descriptor |

## Metrics

| Metric | Type | Description |
|---|---|---|
| `ws.sessions.active` | Gauge | Number of currently connected WebSocket clients |
| `ws.sessions.total{event}` | Counter | Session lifecycle events (`opened` / `closed`) |
| `ws.broadcasts.total{type}` | Counter | Broadcast events by message type (`snapshot` / `heartbeat`) |

## Build and run locally

```bash
mvn clean package -DskipTests -pl services/ws-server
java -jar services/ws-server/target/prototype.flightdelays.ws-server-0.1.0-SNAPSHOT.jar
```

## Docker

```bash
docker build -t flight-tracker/ws-server services/ws-server
```
