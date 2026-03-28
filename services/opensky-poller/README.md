# opensky-poller

Polls the OpenSky Network REST API for live ADS-B aircraft position data and writes state vectors to Redis.

## What it does

- Authenticates with OpenSky Network using OAuth2 client credentials flow (optional -- falls back to unauthenticated access)
- Polls `GET /api/states/all` on a configurable interval (default 15 seconds)
- Parses each state vector into a `StateVector` record (icao24, callsign, lon, lat, altitude, onGround, velocity, heading)
- Writes each position to Redis key `flight:position:{icao24}` with a 60-second TTL
- Refreshes the OAuth2 bearer token automatically 30 seconds before expiry

## Configuration

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `METRICS_PORT` | `8081` | Port for the Prometheus `/metrics` endpoint |
| `OPENSKY_POLL_INTERVAL_SEC` | `15` | Seconds between polls (keep at 15 to stay within 8000 credits/day) |
| `OPENSKY_CLIENT_ID` | _(empty)_ | OpenSky OAuth2 client ID (optional -- polls without auth if blank) |
| `OPENSKY_CLIENT_SECRET` | _(empty)_ | OpenSky OAuth2 client secret |

## Ports

| Port | Purpose |
|---|---|
| 8081 | Prometheus metrics (`/metrics`) |

## Dependencies

- **Redis** -- writes `flight:position:{icao24}` keys (Lettuce client)
- **OpenSky Network API** -- `https://opensky-network.org/api/states/all`

## Key files

| File | Description |
|---|---|
| `src/main/java/.../opensky/OpenSkyPollerApp.java` | Main class: scheduler, OAuth2 token flow, poll loop, Redis writes |
| `src/main/java/.../opensky/StateVector.java` | Record holding parsed ADS-B position fields |
| `src/main/resources/application.properties` | Default configuration values |
| `src/main/resources/logback.xml` | Logging configuration |
| `Dockerfile` | Multi-stage Docker image (eclipse-temurin:21-jre-alpine) |
| `pom.xml` | Maven module descriptor |

## Metrics

| Metric | Type | Description |
|---|---|---|
| `opensky.polls.total{status}` | Counter | Poll attempts by outcome (`success` / `failure`) |
| `opensky.poll.duration.seconds` | Histogram | Time taken per poll cycle |
| `opensky.state_vectors.count` | Gauge | Number of state vectors written in the last poll |
| `opensky.token.refresh.total{status}` | Counter | OAuth2 token refresh attempts by outcome |

## Tests

```bash
mvn test -pl services/opensky-poller
```

12 tests covering StateVector record serialization/deserialization and OpenSky API response array parsing (null coordinates, blank callsigns, empty icao24).

## Build and run locally

```bash
mvn clean package -DskipTests -pl services/opensky-poller
java -jar services/opensky-poller/target/prototype.flightdelays.opensky-poller-0.1.0-SNAPSHOT.jar
```

## Docker

```bash
docker build -t flight-tracker/opensky-poller services/opensky-poller
```
