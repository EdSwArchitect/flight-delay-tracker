# Flight Delay Tracker — Claude Code Context

This file is read automatically by Claude Code on every session.
Do not delete it. Update it as the implementation evolves.

---

## Project identity

- **Name:** Flight Delay Tracker Prototype
- **Maven groupId:** `com.bscllc`
- **Maven artifactId:** `prototype.flightdelays` (parent)
- **Version:** `0.1.0-SNAPSHOT`
- **Java version:** 21

---

## What this project is

A real-time flight tracking prototype that:
1. Polls **OpenSky Network** for live ADS-B aircraft positions every 10–15 seconds
2. Polls **AirLabs Data API** for flight schedules (every 10 min) and delays (every 2 min)
3. Joins position data to schedule data via callsign normalisation
4. Persists hot state in Redis, history in Postgres
5. Serves data via a Javalin REST API and WebSocket server
6. Renders a live flight map in React + MapLibre GL JS + Deck.gl
7. Exposes Micrometer metrics to Prometheus, visualised in Grafana
8. Runs entirely in a local Kind (Kubernetes-in-Docker) cluster via Helm + Skaffold

---

## Repository layout

```
flight-tracker/
├── CLAUDE.md                        ← you are here
├── pom.xml                          ← Maven parent POM
├── skaffold.yaml
├── scripts/
│   ├── start.sh                     ← full cluster bootstrap (chmod +x)
│   └── stop.sh                      ← cluster teardown (chmod +x)
├── k8s/
│   ├── kind-config.yaml
│   └── schema.sql
├── charts/
│   ├── flight-tracker/              ← umbrella Helm chart
│   │   ├── Chart.yaml
│   │   ├── values.yaml
│   │   └── charts/                  ← one subchart per service
│   │       ├── opensky-poller/
│   │       ├── airlabs-poller/
│   │       ├── join-service/
│   │       ├── api/
│   │       ├── ws-server/
│   │       └── map-ui/
│   └── deps/                        ← third-party chart value overrides
│       ├── redis-values.yaml
│       ├── postgres-values.yaml
│       ├── ingress-values.yaml
│       ├── kube-prometheus-values.yaml
│       ├── loki-values.yaml
│       └── alloy-values.yaml
└── services/
    ├── opensky-poller/              ← Java 25, polls OpenSky positions
    ├── airlabs-poller/              ← Java 25, polls AirLabs delays + schedules
    ├── join-service/                ← Java 25, merges position + schedule data
    ├── api/                         ← Java 25, Javalin REST API
    ├── ws-server/                   ← Java 25, Javalin WebSocket broadcaster
    └── map-ui/                      ← TypeScript, React + MapLibre + Deck.gl
```

---

## Maven module structure

| Module directory        | artifactId                                    | Main class |
|------------------------|-----------------------------------------------|------------|
| services/opensky-poller | prototype.flightdelays.opensky-poller        | com.bscllc.flightdelays.opensky.OpenSkyPollerApp |
| services/airlabs-poller | prototype.flightdelays.airlabs-poller        | com.bscllc.flightdelays.airlabs.AirLabsPollerApp |
| services/join-service   | prototype.flightdelays.join-service          | com.bscllc.flightdelays.join.FlightJoinApp |
| services/api            | prototype.flightdelays.api                   | com.bscllc.flightdelays.api.FlightApiApp |
| services/ws-server      | prototype.flightdelays.ws-server             | com.bscllc.flightdelays.ws.WsServerApp |

---

## Key technology decisions

| Concern | Decision | Reason |
|---|---|---|
| Scheduling | Java ScheduledExecutorService (virtual thread factory) | No Python/Celery — single language backend |
| REST framework | Javalin 6 with virtual threads | Minimal, explicit, fast Skaffold loop |
| Metrics | Micrometer + Prometheus registry | /metrics endpoint, compatible with kube-prometheus-stack |
| Redis client | Lettuce (async) | Non-blocking, works naturally with virtual threads |
| Object storage | Postgres JSONB raw_api_responses table | MinIO community edition archived 2026 — deferred to SeaweedFS in production |
| Map tiles | MapTiler free tier | Avoids tileserver-gl PVC complexity for prototype |
| Join strategy | Callsign normalisation (3-step chain) | OpenSky gives icao24+callsign; AirLabs gives flight_iata — no shared key |

---

## Domain model — sealed type hierarchy

```java
// In package com.bscllc.flightdelays.join

public sealed interface FlightRecord
    permits ResolvedFlight, PositionOnlyFlight, UnresolvableFlight {
    String icao24();
}

public record ResolvedFlight(
    StateVector position,       // from opensky-poller
    FlightSchedule schedule,    // from airlabs-poller
    FlightDelay delay,          // null if no active delay
    Instant resolvedAt
) implements FlightRecord {}

public record PositionOnlyFlight(
    StateVector position,
    String callsign,
    Instant resolvedAt
) implements FlightRecord {}

public record UnresolvableFlight(
    StateVector position,
    String reason   // blank_callsign | no_match | normalisation_miss
) implements FlightRecord {}
```

StateVector is in package `com.bscllc.flightdelays.opensky`.
FlightSchedule and FlightDelay are in package `com.bscllc.flightdelays.airlabs`.

---

## Join resolution algorithm

The join-service resolves each OpenSky StateVector to a FlightSchedule in 3 steps:

1. **Exact match** — `callsign.strip()` against `callsign:index` Redis key (Map<String, FlightSchedule>)
2. **ICAO prefix normalisation** — map ICAO airline prefix to IATA (AAL→AA, BAW→BA, DAL→DL, UAL→UA, SWA→WN, AWE→US, FFT→F9, JBU→B6, SKW→OO, ENY→MQ) and retry
3. **Fallback** — produce PositionOnlyFlight if normalisation also misses; UnresolvableFlight if callsign is blank

After resolution, load `flight:delay:{flight_iata}` from Redis to attach delay minutes.
Write result to `flight:enriched:{icao24}` with TTL 70s.

---

## Redis key structure

| Key pattern | TTL | Written by | Read by |
|---|---|---|---|
| `flight:position:{icao24}` | 60s | opensky-poller | join-service |
| `flight:enriched:{icao24}` | 70s | join-service | ws-server, api |
| `flight:delay:{flight_iata}` | 300s | airlabs-poller | join-service, api |
| `callsign:index` | 600s | airlabs-poller | join-service |

Redis db=0 for all flight state. No auth in local dev (bitnami chart: auth.enabled: false).

---

## Postgres schema

```sql
-- Upserted by airlabs-poller every 10 minutes
CREATE TABLE flight_schedules (
    flight_iata TEXT NOT NULL, dep_iata TEXT, arr_iata TEXT,
    dep_time TIMESTAMPTZ NOT NULL, arr_time TIMESTAMPTZ,
    dep_estimated TIMESTAMPTZ, arr_estimated TIMESTAMPTZ,
    status TEXT, delayed_min INTEGER,
    ingested_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (flight_iata, dep_time)
);

-- Partitioned position track (by day)
CREATE TABLE flight_positions (
    icao24 TEXT NOT NULL, callsign TEXT,
    lat DOUBLE PRECISION, lon DOUBLE PRECISION,
    altitude DOUBLE PRECISION, velocity DOUBLE PRECISION,
    heading DOUBLE PRECISION, recorded_at TIMESTAMPTZ NOT NULL
) PARTITION BY RANGE (recorded_at);

-- Status change / join quality events
CREATE TABLE flight_events (
    id BIGSERIAL PRIMARY KEY, flight_iata TEXT, icao24 TEXT,
    event_type TEXT, payload JSONB, created_at TIMESTAMPTZ DEFAULT now()
);

-- Raw API archive (replaces MinIO for local prototype)
CREATE TABLE raw_api_responses (
    id BIGSERIAL PRIMARY KEY, source TEXT NOT NULL,
    endpoint TEXT NOT NULL, payload JSONB NOT NULL,
    recorded_at TIMESTAMPTZ DEFAULT now()
);
```

---

## Service ports

| Service | HTTP port | Metrics port |
|---|---|---|
| opensky-poller | — | 8081 |
| airlabs-poller | — | 8082 |
| join-service | — | 8083 |
| api | 8084 | 8085 |
| ws-server | 8086 | 8087 |
| map-ui | 80 | — |

All Java services expose `/metrics` on their metrics port for Prometheus scraping.
Pod annotations: `prometheus.io/scrape: "true"`, `prometheus.io/port: "{metricsPort}"`, `prometheus.io/path: "/metrics"`.

---

## Kubernetes namespaces

| Namespace | Contents |
|---|---|
| `ingestion` | opensky-poller, airlabs-poller, join-service |
| `api` | api, ws-server |
| `ui` | map-ui |
| `data` | Redis (bitnami/redis), Postgres (bitnami/postgresql) |
| `observability` | kube-prometheus-stack, Loki, Alloy |
| `ingress` | ingress-nginx |

---

## Nginx ingress routing

| Path | Backend | Notes |
|---|---|---|
| `/ws` | ws-server:8086 | proxy-read-timeout: 3600 for WebSocket |
| `/api` | api:8084 | rewrite-target strips /api prefix |
| `/grafana` | kube-prometheus-grafana:80 | GF_SERVER_ROOT_URL must be set |
| `/` | map-ui:80 | SPA catch-all |

---

## Kubernetes secrets (created manually — never committed to Git)

```bash
kubectl create secret generic opensky-credentials --namespace ingestion \
  --from-literal=client-id=<your-client-id> \
  --from-literal=client-secret=<your-client-secret>

kubectl create secret generic airlabs-credentials --namespace ingestion \
  --from-literal=api-key=<your-api-key>

kubectl create secret generic postgres-credentials --namespace data \
  --from-literal=password=flightpass
```

---

## Prometheus metrics reference

| Metric | Type | Service |
|---|---|---|
| `opensky.polls.total{status}` | Counter | opensky-poller |
| `opensky.poll.duration.seconds` | Histogram | opensky-poller |
| `opensky.state_vectors.count` | Gauge | opensky-poller |
| `opensky.token.refresh.total{status}` | Counter | opensky-poller |
| `airlabs.polls.total{endpoint,status}` | Counter | airlabs-poller |
| `airlabs.delays.count` | Gauge | airlabs-poller |
| `airlabs.schedules.count` | Gauge | airlabs-poller |
| `airlabs.index.size` | Gauge | airlabs-poller |
| `airlabs.parse.errors` | Counter | airlabs-poller |
| `flight.joins.total{result}` | Counter | join-service |
| `flight.join.duration.seconds` | Histogram | join-service |
| `flight.enriched.count` | Gauge | join-service |
| `flight.index.miss.reasons{reason}` | Counter | join-service |
| `http.requests.total{method,path,status}` | Counter | api |
| `http.request.duration.seconds{method,path}` | Histogram | api |
| `ws.sessions.active` | Gauge | ws-server |
| `ws.sessions.total{event}` | Counter | ws-server |
| `ws.broadcasts.total{type}` | Counter | ws-server |

---

## map-ui summary

- **Framework:** React 18 + TypeScript + Vite 5
- **Map:** MapLibre GL JS 4 via react-map-gl
- **Overlay:** Deck.gl 9 ScatterplotLayer
- **Tile source:** MapTiler free tier (VITE_MAPTILER_KEY env var)
- **WebSocket:** connects to `/ws/positions`, handles snapshot/delta/heartbeat envelopes
- **Delay colours:** null→grey `[136,135,128]`, ≤0→green `[57,158,117]`, 1-30→amber `[239,159,39]`, 31-60→orange `[216,90,48]`, >60→red `[226,75,74]`
- **Click:** opens FlightDetailPanel, fetches GET /api/flights/{icao24}

---

## Development workflow

```bash
# Full cluster start (builds Maven, Docker, loads into Kind, starts Skaffold)
./scripts/start.sh

# Teardown
./scripts/stop.sh

# Dev loop only (cluster already running)
skaffold dev --profile=local --port-forward

# Build only
mvn clean package -DskipTests
skaffold build --profile=local

# Tail logs
kubectl logs -n ingestion -l app=opensky-poller -f
kubectl logs -n ingestion -l app=airlabs-poller -f
kubectl logs -n ingestion -l app=join-service -f
kubectl logs -n api -l app=api -f
kubectl logs -n api -l app=ws-server -f

# Check all pods
kubectl get pods -A

# Inspect Redis
kubectl exec -n data deploy/redis-master -- redis-cli KEYS 'flight:*' | head -20
kubectl exec -n data deploy/redis-master -- redis-cli GET flight:enriched:<icao24>

# Access points
# Map UI:    http://localhost:8080
# REST API:  http://localhost:8080/api/flights
# WebSocket: ws://localhost:8080/ws/positions
# Grafana:   http://localhost:8080/grafana  (admin/admin)
# Prometheus: http://localhost:9090
```

---

## Current implementation status

Update this section as work progresses.

- [x] Phase 1 — Infrastructure (Kind, Helm deps, start.sh, stop.sh)
- [x] Phase 2 — Maven build, Dockerfiles, Skaffold
- [x] Phase 3.1 — OpenSky client + OAuth2 token flow
- [x] Phase 3.2 — OpenSky poller + Redis write
- [x] Phase 3.3 — AirLabs client (delays + schedules)
- [x] Phase 3.4 — Callsign index + Postgres schedule upsert
- [x] Phase 3.5 — Join service
- [x] Phase 4.1 — REST API endpoints
- [x] Phase 4.2 — WebSocket broadcaster
- [x] Phase 4.3 — Nginx ingress wiring
- [x] Phase 4.4 — map-ui WebSocket + Deck.gl layer
- [x] Phase 5.1 — Prometheus annotations verified
- [x] Phase 5.2 — Grafana dashboard ConfigMaps
- [x] Phase 5.3 — Resource limits + liveness probes
- [x] Phase 5.4 — End-to-end validation runbook

---

## Notes and decisions log

Add dated notes here as implementation decisions are made.

```
# Example format:
# 2026-03-28: Set OPENSKY_POLL_INTERVAL_SEC=15 (default) to stay within 8000 credit/day limit
# 2026-03-28: AirLabs delay endpoint returns array at root (not wrapped in {"response": [...]})
```
