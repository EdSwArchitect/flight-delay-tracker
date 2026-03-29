# Flight Delay Tracker

Real-time flight tracking prototype that joins ADS-B position data with airline schedule and delay information, rendered on a live map.

<!-- screenshot placeholder -->

## What It Does

- **Polls OpenSky Network** for live ADS-B aircraft positions every 10-15 seconds, writing state vectors to Redis
- **Polls AirLabs Data API** for flight schedules (every 10 min) and active delays (every 2 min), upserting to Postgres and Redis
- **Joins position and schedule data** via a 3-step callsign normalisation algorithm (ICAO-to-IATA prefix mapping)
- **Serves enriched flight data** through a REST API and real-time WebSocket broadcast
- **Renders a live flight map** in the browser using React, MapLibre GL JS, and Deck.gl with colour-coded delay indicators
- **Exposes operational metrics** to Prometheus and Grafana for monitoring poll rates, join quality, and WebSocket sessions

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Javalin 6, Lettuce (async Redis), Micrometer, JUnit 5 |
| Frontend | React 18, TypeScript, MapLibre GL JS 4, Deck.gl 9, Vite 5 |
| Data stores | Redis (hot state, TTL-based), PostgreSQL (history, JSONB archive) |
| Orchestration | Kind (Kubernetes-in-Docker), Helm 3, Skaffold |
| Observability | Prometheus, Grafana, Loki, Alloy (log collector) |
| Infrastructure | Nginx Ingress, Bitnami Redis/PostgreSQL charts |

## Architecture Overview

The system is organised as a microservices pipeline running across 6 Kubernetes namespaces (`ingestion`, `api`, `ui`, `data`, `observability`, `ingress`). Data flows from two external APIs through ingestion pollers, into a join service that enriches positions with schedule and delay information, then out to clients via REST and WebSocket endpoints.

For a detailed breakdown of each service, the domain model, join algorithm, data store schemas, and Kubernetes topology, see [docs/architecture.md](docs/architecture.md).

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Docker | 20.10+ | Docker Desktop or colima |
| Kind | 0.20+ | `brew install kind` |
| Helm | 3.12+ | `brew install helm` |
| Skaffold | 2.x | `brew install skaffold` |
| Java | 21 | SDKMAN recommended: `sdk install java 21-open` |
| Maven | 3.9+ | `sdk install maven` or `brew install maven` |
| Node.js | 20+ | For map-ui development |

## Quick Start

### 1. Clone the repository

```bash
git clone <repo-url>
cd flight-delay-tracker
```

### 2. Create Kubernetes secrets

These secrets are never committed to Git.

**AirLabs API key** — place your key in a file called `airlabs-key.txt` in the project root (already in `.gitignore`). The `start.sh` script reads this file and creates the `airlabs-credentials` secret automatically.

**OpenSky and Postgres** — create these manually after the cluster is running:

```bash
kubectl create secret generic opensky-credentials --namespace default \
  --from-literal=client-id=<your-client-id> \
  --from-literal=client-secret=<your-client-secret>

kubectl create secret generic postgres-credentials --namespace data \
  --from-literal=password=flightpass
```

### 3. Start the cluster

```bash
./scripts/start.sh
```

This bootstraps the full Kind cluster, installs Helm dependencies, applies the database schema, builds all services, and starts Skaffold.

### 4. Access the application

| Endpoint | URL |
|---|---|
| Map UI | http://localhost:8080 |
| REST API | http://localhost:8080/api/flights |
| WebSocket | ws://localhost:8080/ws/positions |
| Grafana | http://localhost:8080/grafana (admin/admin) |
| Prometheus | http://localhost:9090 |

## Project Structure

```
flight-delay-tracker/
├── pom.xml                  Maven parent POM (multi-module)
├── skaffold.yaml            Skaffold configuration for local dev
├── scripts/
│   ├── start.sh             Full cluster bootstrap
│   └── stop.sh              Cluster teardown
├── k8s/
│   ├── kind-config.yaml     Kind cluster configuration
│   ├── schema.sql           Postgres schema (4 tables)
│   └── validation-runbook.sh  End-to-end deployment validation
├── charts/
│   ├── flight-tracker/      Umbrella Helm chart with per-service subcharts
│   └── deps/                Value overrides for third-party Helm charts
├── services/
│   ├── opensky-poller/      Polls OpenSky Network for ADS-B positions
│   ├── airlabs-poller/      Polls AirLabs for schedules and delays
│   ├── join-service/        Joins position data with schedule data
│   ├── api/                 REST API (Javalin)
│   ├── ws-server/           WebSocket broadcaster (Javalin)
│   └── map-ui/              React frontend (MapLibre + Deck.gl)
└── docs/
    └── architecture.md      Detailed architecture documentation
```

## Services

| Service | Description | Port(s) | Namespace |
|---|---|---|---|
| opensky-poller | Polls OpenSky Network API for live ADS-B state vectors, writes to Redis | 8081 (metrics) | ingestion |
| airlabs-poller | Polls AirLabs API for flight schedules and delays, writes to Redis and Postgres | 8082 (metrics) | ingestion |
| join-service | Joins position and schedule data via callsign normalisation, writes enriched records to Redis | 8083 (metrics) | ingestion |
| api | REST API serving enriched flight data and delay information | 8084 (HTTP), 8085 (metrics) | api |
| ws-server | WebSocket server broadcasting real-time position updates to connected clients | 8086 (WS), 8087 (metrics) | api |
| map-ui | React SPA rendering flights on an interactive map with delay colour coding | 80 (HTTP) | ui |

## Configuration

### Environment Variables

| Variable | Service | Default | Description |
|---|---|---|---|
| `OPENSKY_CLIENT_ID` | opensky-poller | (secret) | OpenSky Network OAuth2 client ID |
| `OPENSKY_CLIENT_SECRET` | opensky-poller | (secret) | OpenSky Network OAuth2 client secret |
| `OPENSKY_POLL_INTERVAL_SEC` | opensky-poller | 30 | Seconds between OpenSky polls (backs off exponentially on 429) |
| `AIRLABS_API_KEY` | airlabs-poller | (secret) | AirLabs Data API key |
| `DEP_AIRPORT` | airlabs-poller | BWI | IATA code for departure airport filter |
| `AIRLABS_SCHEDULE_INTERVAL_SEC` | airlabs-poller | 600 | Seconds between schedule polls |
| `AIRLABS_DELAY_INTERVAL_SEC` | airlabs-poller | 120 | Seconds between delay polls |
| `REDIS_HOST` | all Java services | redis-master.data | Redis hostname |
| `REDIS_PORT` | all Java services | 6379 | Redis port |
| `POSTGRES_HOST` | airlabs-poller | postgres-postgresql.data | Postgres hostname |
| `POSTGRES_PASSWORD` | airlabs-poller | (secret) | Postgres password |
| `VITE_MAPTILER_KEY` | map-ui | (required) | MapTiler API key for map tiles |

## Testing

```bash
mvn test
```

50 JUnit 5 tests across all services:

| Service | Tests | Coverage |
|---------|-------|----------|
| opensky-poller | 12 | StateVector serialization, OpenSky response parsing |
| airlabs-poller | 19 | FlightSchedule/FlightDelay records, AirLabs response parsing, error handling |
| join-service | 21 | CallsignResolver 3-step chain, all 10 ICAO-to-IATA mappings |
| api | 6 | REST JSON contract (health, flights, delays, errors) |
| ws-server | 12 | WebSocket envelope format, concurrent session tracking |

## Development

### Build

```bash
mvn clean package -DskipTests
```

### Dev loop (cluster already running)

```bash
skaffold dev --profile=local --port-forward
```

### Build Docker images only

```bash
skaffold build --profile=local
```

### Tail service logs

```bash
kubectl logs -n ingestion -l app=opensky-poller -f
kubectl logs -n ingestion -l app=airlabs-poller -f
kubectl logs -n ingestion -l app=join-service -f
kubectl logs -n api -l app=api -f
kubectl logs -n api -l app=ws-server -f
```

### Check pod status

```bash
kubectl get pods -A
```

### Inspect Redis

```bash
kubectl exec -n data deploy/redis-master -- redis-cli KEYS 'flight:*' | head -20
kubectl exec -n data deploy/redis-master -- redis-cli GET flight:enriched:<icao24>
```

### Validate deployment

```bash
bash k8s/validation-runbook.sh
```

Runs 8-stage checks: cluster, namespaces, pods, Redis data, Postgres data, HTTP endpoints, metrics, and ingress.

## Cleanup

```bash
./scripts/stop.sh
```

This tears down the Kind cluster and all associated resources.

## License

TBD
