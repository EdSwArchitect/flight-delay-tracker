# join-service

Merges OpenSky position data with AirLabs schedule and delay data using a 3-step callsign resolution algorithm, then writes enriched flight records to Redis.

## What it does

- Runs a join cycle on a configurable interval (default 10 seconds)
- Scans all `flight:position:*` keys and loads the `callsign:index` hash from Redis
- Resolves each position to a schedule using a 3-step algorithm:
  1. **Exact match** -- stripped callsign looked up directly in the callsign index
  2. **ICAO prefix normalisation** -- maps 3-letter ICAO airline prefixes to 2-letter IATA codes (AAL->AA, BAW->BA, DAL->DL, UAL->UA, SWA->WN, AWE->US, FFT->F9, JBU->B6, SKW->OO, ENY->MQ) and retries the lookup
  3. **Fallback** -- produces a `position_only` record if normalisation misses, or `unresolvable` if callsign is blank
- Attaches delay minutes from `flight:delay:{flight_iata}` when a match is found
- Writes enriched results to `flight:enriched:{icao24}` with a 70-second TTL

## Configuration

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `METRICS_PORT` | `8083` | Port for the Prometheus `/metrics` endpoint |
| `JOIN_INTERVAL_SEC` | `10` | Seconds between join cycles |

## Ports

| Port | Purpose |
|---|---|
| 8083 | Prometheus metrics (`/metrics`) |

## Dependencies

- **Redis** -- reads `flight:position:*`, `callsign:index`, `flight:delay:*`; writes `flight:enriched:*` (Lettuce client)

## Key files

| File | Description |
|---|---|
| `src/main/java/.../join/FlightJoinApp.java` | Main class: scheduler, scan loop, 3-step resolution algorithm, enriched record writes |
| `src/main/resources/application.properties` | Default configuration values |
| `src/main/resources/logback.xml` | Logging configuration |
| `Dockerfile` | Multi-stage Docker image (eclipse-temurin:21-jre-alpine) |
| `pom.xml` | Maven module descriptor |

## Metrics

| Metric | Type | Description |
|---|---|---|
| `flight.joins.total{result}` | Counter | Join outcomes by type (`resolved`, `position_only`, `unresolvable`) |
| `flight.join.duration.seconds` | Histogram | Time taken per join cycle |
| `flight.enriched.count` | Gauge | Number of enriched records written in the last cycle |
| `flight.index.miss.reasons{reason}` | Counter | Resolution miss reasons (`blank_callsign`, `no_match`, `normalisation_miss`) |

## Build and run locally

```bash
mvn clean package -DskipTests -pl services/join-service
java -jar services/join-service/target/prototype.flightdelays.join-service-0.1.0-SNAPSHOT.jar
```

## Docker

```bash
docker build -t flight-tracker/join-service services/join-service
```
