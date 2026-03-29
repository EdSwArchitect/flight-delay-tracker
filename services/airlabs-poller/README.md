# airlabs-poller

Polls the AirLabs Data API for flight schedules and delay information, builds a callsign lookup index in Redis, and persists schedule history to Postgres.

## What it does

- Polls the AirLabs `/schedules` endpoint every 10 minutes for departures from a configurable airport (default BWI)
- Polls the AirLabs `/delays` endpoint every 2 minutes for active delay data
- Builds and maintains a `callsign:index` Redis hash mapping flight IATA codes to schedule JSON (600s TTL)
- Upserts schedule records into the Postgres `flight_schedules` table
- Writes delay data to Redis keys `flight:delay:{flight_iata}` with a 300-second TTL
- Archives every raw API response into the Postgres `raw_api_responses` table as JSONB
- Skips schedule entries with missing or unparseable `dep_time` to avoid Postgres NOT NULL constraint violations
- Degrades gracefully when `AIRLABS_API_KEY` is missing -- metrics server stays running for liveness probes, polling is disabled
- API key secret is auto-created by `start.sh` from `airlabs-key.txt` in the project root (file is in `.gitignore`)

## Configuration

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `METRICS_PORT` | `8082` | Port for the Prometheus `/metrics` endpoint |
| `SCHEDULE_POLL_INTERVAL_SEC` | `600` | Seconds between schedule polls |
| `DELAY_POLL_INTERVAL_SEC` | `120` | Seconds between delay polls |
| `POSTGRES_HOST` | `localhost` | Postgres hostname |
| `POSTGRES_PORT` | `5432` | Postgres port |
| `POSTGRES_DB` | `flightdb` | Postgres database name |
| `POSTGRES_USER` | `flightuser` | Postgres username |
| `POSTGRES_PASSWORD` | `flightpass` | Postgres password |
| `AIRLABS_API_KEY` | _(empty)_ | AirLabs API key (polling disabled if blank) |
| `DEP_AIRPORT` | `BWI` | IATA code of the departure airport to filter on |

## Ports

| Port | Purpose |
|---|---|
| 8082 | Prometheus metrics (`/metrics`) |

## Dependencies

- **Redis** -- writes `callsign:index` hash and `flight:delay:*` keys (Lettuce client)
- **Postgres** -- upserts to `flight_schedules`, inserts to `raw_api_responses` (HikariCP pool)
- **AirLabs Data API** -- `https://airlabs.co/api/v9/schedules` and `/delays`

## Key files

| File | Description |
|---|---|
| `src/main/java/.../airlabs/AirLabsPollerApp.java` | Main class: dual schedulers, schedule/delay poll loops, Postgres upserts, raw archiving |
| `src/main/java/.../airlabs/FlightSchedule.java` | Record for schedule data (flightIata, dep/arr airports, times, status, delay) |
| `src/main/java/.../airlabs/FlightDelay.java` | Record for delay data (flightIata, delayedMin, recordedAt) |
| `src/main/resources/application.properties` | Default configuration values |
| `src/main/resources/logback.xml` | Logging configuration |
| `Dockerfile` | Multi-stage Docker image (eclipse-temurin:21-jre-alpine) |
| `pom.xml` | Maven module descriptor |

## Metrics

| Metric | Type | Description |
|---|---|---|
| `airlabs.polls.total{endpoint,status}` | Counter | Poll attempts by endpoint (`schedules`/`delays`) and outcome |
| `airlabs.delays.count` | Gauge | Number of delays written in the last delay poll |
| `airlabs.schedules.count` | Gauge | Number of schedules upserted in the last schedule poll |
| `airlabs.index.size` | Gauge | Number of entries in the `callsign:index` hash |
| `airlabs.parse.errors` | Counter | Individual entry parse/processing failures |

## Tests

```bash
mvn test -pl services/airlabs-poller
```

19 tests covering FlightSchedule/FlightDelay record serialization, AirLabs response parsing (schedule and delay endpoints), error response detection for invalid API keys, nullable field handling, and bare vs wrapped array formats.

## Build and run locally

```bash
mvn clean package -DskipTests -pl services/airlabs-poller
java -jar services/airlabs-poller/target/prototype.flightdelays.airlabs-poller-0.1.0-SNAPSHOT.jar
```

## Docker

```bash
docker build -t flight-tracker/airlabs-poller services/airlabs-poller
```
