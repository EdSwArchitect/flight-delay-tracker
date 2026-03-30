# Flight Delay Tracker -- Architecture

## System Overview

The Flight Delay Tracker is a microservices pipeline running in a local Kubernetes cluster (Kind). It ingests real-time aircraft position data from the OpenSky Network and flight schedule/delay data from the AirLabs API, joins them through a callsign normalisation algorithm, and delivers enriched flight records to clients via REST and WebSocket endpoints. A React-based map UI renders flights in real time with colour-coded delay indicators. The entire system is observable through Prometheus metrics, Grafana dashboards, and Loki log aggregation.

The pipeline is designed around short-lived, TTL-based Redis keys for hot state and PostgreSQL for durable history, with no inter-service RPC -- all communication flows through shared data stores.

## Data Flow

```
OpenSky Network API              AirLabs API
      |                               |
      v                               v
[opensky-poller]              [airlabs-poller]
      |                          |    |    |
      | flight:position:*        |    |    | flight:delay:*
      |                          |    |    | callsign:index
      v                          |    |    v
     Redis  <--------------------+    |   Redis
      |                               |
      |                               v
      |                          PostgreSQL
      |                     flight_schedules table
      |                               |
      +---------------+---------------+
                      |
                      v
               [join-service]
          flight:enriched:* -> Redis
                      |
            +---------+---------+
            v                   v
         [api]            [ws-server]
       REST /flights     WS /ws/positions
            |                   |
            +-------+-----------+
                    v
                [map-ui]
          React + MapLibre + Deck.gl
```

## Service Descriptions

### opensky-poller

**Purpose:** Polls the OpenSky Network REST API for live ADS-B state vectors (aircraft positions, altitude, velocity, heading) and writes them to Redis.

**Key files:**
- `services/opensky-poller/src/main/java/com/bscllc/flightdelays/opensky/OpenSkyPollerApp.java`

**Ports:** 8081 (metrics)

**Dependencies:** Redis (write), OpenSky Network API (external)

**Scheduling:** Polls every 30 seconds by default (configurable via `OPENSKY_POLL_INTERVAL_SEC`). Uses single-shot `schedule()` calls with dynamic delay for exponential backoff: on HTTP 429 or failure, the interval doubles (30s -> 60s -> 120s -> 240s -> 300s cap); on success, it resets to the base interval. Uses `ScheduledExecutorService` with a virtual thread factory. Includes OAuth2 token refresh logic for authenticated access to OpenSky.

**Redis writes:** `flight:position:{icao24}` with 60-second TTL for each state vector.

---

### airlabs-poller

**Purpose:** Polls the AirLabs Data API for flight schedules and active delays. Maintains a callsign-to-schedule index in Redis and upserts schedule records to PostgreSQL.

**Key files:**
- `services/airlabs-poller/src/main/java/com/bscllc/flightdelays/airlabs/AirLabsPollerApp.java`

**Ports:** 8082 (metrics)

**Dependencies:** Redis (write), PostgreSQL (write), AirLabs API (external)

**Scheduling:**
- Schedule poll: every 10 minutes (`AIRLABS_SCHEDULE_INTERVAL_SEC`, default 600)
- Delay poll: every 2 minutes (`AIRLABS_DELAY_INTERVAL_SEC`, default 120)

**Redis writes:**
- `callsign:index` -- hash map of callsign-to-FlightSchedule, TTL 600s
- `flight:delay:{flight_iata}` -- delay minutes per flight, TTL 300s

**Postgres writes:** Upserts into `flight_schedules` table. Archives raw API responses to `raw_api_responses` table.

**Timestamp handling:** AirLabs returns timestamps in multiple formats including `yyyy-MM-dd HH:mm` (no seconds) and ISO offset. The `parseTimestamp()` method uses a 3-step fallback chain: ISO offset date-time, `Timestamp.valueOf()` (with seconds), and `LocalDateTime` parse (without seconds). Schedule entries with unparseable `dep_time` are skipped.

---

### join-service

**Purpose:** Reads position data and schedule data from Redis, resolves each state vector to a flight schedule using callsign normalisation, attaches delay information, and writes enriched flight records back to Redis.

**Key files:**
- `services/join-service/src/main/java/com/bscllc/flightdelays/join/FlightJoinApp.java`

**Ports:** 8083 (metrics)

**Dependencies:** Redis (read and write)

**Scheduling:** Runs on a tight loop, processing new position keys as they appear. Each join cycle scans `flight:position:*` keys, resolves them, and writes `flight:enriched:{icao24}` with 70-second TTL.

---

### api

**Purpose:** Serves enriched flight data via a Javalin REST API. Reads from Redis enriched keys and delay keys.

**Key files:**
- `services/api/src/main/java/com/bscllc/flightdelays/api/FlightApiApp.java`

**Ports:** 8084 (HTTP), 8085 (metrics)

**Dependencies:** Redis (read)

**Endpoints:**
- `GET /flights` -- returns all enriched flight records
- `GET /flights/{icao24}` -- returns a single enriched flight record with full detail

---

### ws-server

**Purpose:** WebSocket server that broadcasts enriched flight positions to connected browser clients. Supports snapshot (full state on connect), delta (incremental updates), and heartbeat message envelopes.

**Key files:**
- `services/ws-server/src/main/java/com/bscllc/flightdelays/ws/WsServerApp.java`

**Ports:** 8086 (WebSocket), 8087 (metrics)

**Dependencies:** Redis (read)

**WebSocket path:** `/ws/positions`

**Message types:**
- `snapshot` -- full set of enriched flights, sent on initial connection
- `delta` -- changed flights since last broadcast
- `heartbeat` -- keepalive signal

---

### map-ui

**Purpose:** Browser-based React SPA that renders live flight positions on an interactive map. Connects to the WebSocket server for real-time updates and displays flight detail popups using locally cached data.

**Key files:**
- `services/map-ui/src/App.tsx` -- main application component
- `services/map-ui/src/hooks/useFlightWebSocket.ts` -- WebSocket connection hook
- `services/map-ui/src/components/FlightDetailPanel.tsx` -- flight info popup positioned at clicked dot
- `services/map-ui/src/components/DelayLegend.tsx` -- delay colour legend
- `services/map-ui/src/utils/delayColor.ts` -- delay-to-colour mapping

**Ports:** 80 (HTTP, served by Nginx)

**Dependencies:** ws-server (WebSocket)

**Click interaction:** Clicking a flight dot opens a `FlightPopup` positioned at the click location. The popup uses locally cached WebSocket data (no API round-trip) and displays: flight IATA / callsign header, route bar (DEP --- ARR), colour-coded delay badge, altitude (ft), speed (kts), heading, departure/arrival times, estimated times, and resolution type. The popup border colour matches the flight's delay tier. Clicking the map or the close button dismisses it.

**Delay colour coding:**
| Delay | Colour | RGB |
|---|---|---|
| Unknown (null) | Grey | [136, 135, 128] |
| On time (0 or less) | Green | [57, 158, 117] |
| 1-30 minutes | Amber | [239, 159, 39] |
| 31-60 minutes | Orange | [216, 90, 48] |
| Over 60 minutes | Red | [226, 75, 74] |

## Domain Model

The join-service produces one of three flight record variants for each observed aircraft, modelled as a sealed interface hierarchy:

```java
public sealed interface FlightRecord
    permits ResolvedFlight, PositionOnlyFlight, UnresolvableFlight {
    String icao24();
}
```

### ResolvedFlight

Produced when the join algorithm successfully matches a position to a flight schedule. Contains the full StateVector (position, altitude, velocity, heading), the FlightSchedule (departure/arrival airports, times, status), an optional FlightDelay (delay minutes), and a resolution timestamp.

### PositionOnlyFlight

Produced when the aircraft has a valid callsign but no matching schedule was found in the callsign index. The flight appears on the map but without schedule or delay information. This is common for general aviation, military, and cargo flights not covered by AirLabs data.

### UnresolvableFlight

Produced when the join cannot proceed at all. The `reason` field indicates why:
- `blank_callsign` -- the OpenSky state vector has an empty or whitespace-only callsign
- `no_match` -- exact callsign lookup found nothing
- `normalisation_miss` -- ICAO-to-IATA prefix mapping was attempted but still found no match

### Supporting types

- **StateVector** (`com.bscllc.flightdelays.opensky`) -- position data from OpenSky: icao24, callsign, latitude, longitude, altitude, velocity, heading, timestamp
- **FlightSchedule** (`com.bscllc.flightdelays.airlabs`) -- schedule data from AirLabs: flight_iata, departure/arrival airports, scheduled/estimated times, status
- **FlightDelay** (`com.bscllc.flightdelays.airlabs`) -- delay information: flight_iata, delayed_min

## Join Resolution Algorithm

The join-service resolves each OpenSky StateVector to a FlightSchedule through a 3-step process:

### Step 1: Exact Match

Strip whitespace from the callsign and look it up directly in the `callsign:index` Redis key. This hash map is maintained by airlabs-poller and maps callsigns to FlightSchedule JSON. Many commercial flights use their IATA flight number as the callsign (e.g., "AA1234"), so this step resolves the majority of flights.

### Step 2: ICAO Prefix Normalisation

If the exact match fails, attempt to convert an ICAO-format callsign to IATA format. ICAO callsigns use a 3-letter airline prefix (e.g., "AAL1234" for American Airlines flight 1234), while AirLabs indexes by IATA codes (e.g., "AA1234").

The normalisation table:

| ICAO Prefix | IATA Code | Airline |
|---|---|---|
| AAL | AA | American Airlines |
| BAW | BA | British Airways |
| DAL | DL | Delta Air Lines |
| UAL | UA | United Airlines |
| SWA | WN | Southwest Airlines |
| AWE | US | US Airways |
| FFT | F9 | Frontier Airlines |
| JBU | B6 | JetBlue Airways |
| SKW | OO | SkyWest Airlines |
| ENY | MQ | Envoy Air |

Extract the 3-letter prefix, map it to the IATA code, reconstruct the callsign (e.g., "AAL1234" becomes "AA1234"), and retry the lookup against `callsign:index`.

### Step 3: Fallback

If both lookups fail:
- If the callsign was blank or whitespace-only, produce an `UnresolvableFlight` with reason `blank_callsign`
- If the callsign was present but not found, produce a `PositionOnlyFlight` (the aircraft will still appear on the map, just without schedule data)

### Post-Resolution: Delay Attachment

After a successful match, look up `flight:delay:{flight_iata}` in Redis to attach delay minutes. Write the final result (any of the three record types) to `flight:enriched:{icao24}` with a 70-second TTL.

## Data Stores

### Redis Key Structure

All keys use Redis database 0. No authentication in local development.

| Key Pattern | TTL | Written By | Read By | Description |
|---|---|---|---|---|
| `flight:position:{icao24}` | 60s | opensky-poller | join-service | Raw ADS-B state vector for one aircraft |
| `flight:enriched:{icao24}` | 70s | join-service | ws-server, api | Enriched flight record (any FlightRecord variant) |
| `flight:delay:{flight_iata}` | 300s | airlabs-poller | join-service, api | Delay minutes for a specific flight |
| `callsign:index` | 600s | airlabs-poller | join-service | Hash map of callsign to FlightSchedule JSON |

TTLs are staggered intentionally: positions expire at 60s, enriched records at 70s (to outlive their source positions), delays at 300s (refreshed every 120s), and the callsign index at 600s (refreshed every 600s).

### PostgreSQL Schema

The database contains four tables:

**flight_schedules** -- Upserted by airlabs-poller every 10 minutes. Contains IATA flight identifiers, departure/arrival airports, scheduled and estimated times, flight status, and delay minutes. Primary key is (flight_iata, dep_time).

**flight_positions** -- Partitioned by day. Stores historical position tracks: icao24, callsign, latitude, longitude, altitude, velocity, heading, and timestamp. Intended for replay and analytics.

**flight_events** -- Append-only event log for status changes and join quality events. Each event has a type (e.g., schedule change, delay update, join resolution) and a JSONB payload.

**raw_api_responses** -- Archive of raw JSON responses from both OpenSky and AirLabs APIs. Serves as the object storage layer for the prototype (replacing MinIO, which was archived in 2026).

```sql
CREATE TABLE flight_schedules (
    flight_iata TEXT NOT NULL, dep_iata TEXT, arr_iata TEXT,
    dep_time TIMESTAMPTZ NOT NULL, arr_time TIMESTAMPTZ,
    dep_estimated TIMESTAMPTZ, arr_estimated TIMESTAMPTZ,
    status TEXT, delayed_min INTEGER,
    ingested_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (flight_iata, dep_time)
);

CREATE TABLE flight_positions (
    icao24 TEXT NOT NULL, callsign TEXT,
    lat DOUBLE PRECISION, lon DOUBLE PRECISION,
    altitude DOUBLE PRECISION, velocity DOUBLE PRECISION,
    heading DOUBLE PRECISION, recorded_at TIMESTAMPTZ NOT NULL
) PARTITION BY RANGE (recorded_at);

CREATE TABLE flight_events (
    id BIGSERIAL PRIMARY KEY, flight_iata TEXT, icao24 TEXT,
    event_type TEXT, payload JSONB, created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE raw_api_responses (
    id BIGSERIAL PRIMARY KEY, source TEXT NOT NULL,
    endpoint TEXT NOT NULL, payload JSONB NOT NULL,
    recorded_at TIMESTAMPTZ DEFAULT now()
);
```

## Kubernetes Topology

### Namespace Layout

Skaffold deploys all application services to the `default` namespace. Infrastructure dependencies use dedicated namespaces:

| Namespace | Contents | Purpose |
|---|---|---|
| `default` | opensky-poller, airlabs-poller, join-service, api, ws-server, map-ui | All application services (deployed via Skaffold Helm release) |
| `data` | Redis (bitnami/redis), PostgreSQL (bitnami/postgresql) | Data store infrastructure |
| `observability` | kube-prometheus-stack, Loki, Alloy | Monitoring and log aggregation |
| `ingress` | ingress-nginx | External traffic routing |

### Service Discovery

All inter-service communication uses Kubernetes ClusterIP services with DNS-based discovery. For example, the join-service connects to Redis at `redis-master.data.svc.cluster.local:6379`. No service mesh or external load balancer is required for the local prototype.

### Ingress Routing

A single Nginx Ingress routes all external traffic:

| Path | Backend Service | Port | Notes |
|---|---|---|---|
| `/ws` | ws-server | 8086 | proxy-read-timeout set to 3600s for WebSocket upgrade |
| `/api` | api | 8084 | rewrite-target strips the `/api` prefix before forwarding |
| `/grafana` | kube-prometheus-grafana | 80 | Requires `GF_SERVER_ROOT_URL` to be set for sub-path routing |
| `/` | map-ui | 80 | SPA catch-all, serves index.html for all unmatched paths |

## Observability

### Prometheus Metrics

All Java services expose a `/metrics` endpoint on their dedicated metrics port. Pods are annotated for automatic Prometheus scraping:

```yaml
prometheus.io/scrape: "true"
prometheus.io/port: "<metricsPort>"
prometheus.io/path: "/metrics"
```

#### opensky-poller (port 8081)

| Metric | Type | Description |
|---|---|---|
| `opensky.polls.total{status}` | Counter | Total poll attempts, tagged by success/failure |
| `opensky.poll.duration.seconds` | Histogram | Time taken per poll cycle |
| `opensky.state_vectors.count` | Gauge | Number of state vectors in latest poll response |
| `opensky.token.refresh.total{status}` | Counter | OAuth2 token refresh attempts |

#### airlabs-poller (port 8082)

| Metric | Type | Description |
|---|---|---|
| `airlabs.polls.total{endpoint,status}` | Counter | Poll attempts per endpoint (schedules, delays) |
| `airlabs.delays.count` | Gauge | Number of active delays in latest poll |
| `airlabs.schedules.count` | Gauge | Number of schedules in latest poll |
| `airlabs.index.size` | Gauge | Number of entries in the callsign index |
| `airlabs.parse.errors` | Counter | JSON parse errors encountered |

#### join-service (port 8083)

| Metric | Type | Description |
|---|---|---|
| `flight.joins.total{result}` | Counter | Join outcomes: resolved, position_only, unresolvable |
| `flight.join.duration.seconds` | Histogram | Time taken per join cycle |
| `flight.enriched.count` | Gauge | Number of enriched records written |
| `flight.index.miss.reasons{reason}` | Counter | Breakdown of join failures by reason |

#### api (port 8085)

| Metric | Type | Description |
|---|---|---|
| `http.requests.total{method,path,status}` | Counter | HTTP requests by method, path, and status code |
| `http.request.duration.seconds{method,path}` | Histogram | Request latency by method and path |

#### ws-server (port 8087)

| Metric | Type | Description |
|---|---|---|
| `ws.sessions.active` | Gauge | Currently connected WebSocket clients |
| `ws.sessions.total{event}` | Counter | Session lifecycle events (connect, disconnect, error) |
| `ws.broadcasts.total{type}` | Counter | Broadcasts by type (snapshot, delta, heartbeat) |

### Grafana Dashboards

Grafana is accessible at `http://localhost:8080/grafana` (default credentials: admin/admin). Dashboard ConfigMaps are deployed with the kube-prometheus-stack and include:

- **Flight Pipeline Overview** -- poll rates, join resolution rates, enriched record counts
- **API and WebSocket** -- request rates, latencies, active WebSocket sessions
- **Infrastructure** -- Redis memory usage, Postgres connection pool, pod resource utilisation

### Log Aggregation

Loki is deployed in the `observability` namespace alongside Prometheus. It collects logs from all pods and makes them queryable through Grafana's Explore view. Log labels include namespace, pod name, and container name.

## Security Considerations

### Secrets Management

All API credentials and database passwords are stored as Kubernetes secrets, never committed to the repository:

- `opensky-credentials` (namespace: default) -- OpenSky OAuth2 client ID and secret (created manually)
- `airlabs-credentials` (namespace: default) -- AirLabs API key (auto-created by `start.sh` from `airlabs-key.txt` in the project root, which is in `.gitignore`)
- `postgres-credentials` (namespace: data) -- PostgreSQL password (created manually)

Secrets are mounted as environment variables via `secretKeyRef` in the relevant pod specifications. The airlabs-poller maps secret key `api-key` to env var `AIRLABS_API_KEY`.

### Local Development Security Posture

This is a local prototype with relaxed security settings appropriate for development:

- Redis runs without authentication (`auth.enabled: false` in the Bitnami chart)
- PostgreSQL uses a simple password (`flightpass`)
- Grafana uses default credentials (admin/admin)
- No TLS termination at the ingress level
- No network policies between namespaces

These settings are not suitable for production deployment.

## Deployment Pipeline

The `start.sh` script orchestrates the full cluster bootstrap:

1. **Create Kind cluster** -- using `k8s/kind-config.yaml`, which maps host port 8080 to the ingress controller
2. **Create namespaces** -- `data`, `observability`, `ingress` (application services deploy to `default`)
3. **Install Helm dependencies** -- Redis, PostgreSQL, ingress-nginx, kube-prometheus-stack, Loki, and Alloy using value overrides from `charts/deps/`
4. **Apply database schema** -- creates a ConfigMap from `k8s/schema.sql` before Helm install (must be created before PostgreSQL starts, as the Bitnami chart runs init scripts on first boot)
5. **Create secrets** -- auto-creates `airlabs-credentials` in the `default` namespace from `airlabs-key.txt`
6. **Build Java services** -- `mvn clean package -DskipTests` from the project root
7. **Build Docker images** -- Skaffold builds images for all 6 services using their respective Dockerfiles
8. **Load images into Kind** -- images are loaded directly into the Kind node (no registry needed)
9. **Deploy via Skaffold** -- installs the umbrella Helm chart (`charts/flight-tracker/`) which deploys all application services to the `default` namespace
10. **Enable port forwarding** -- Skaffold forwards local ports to the cluster for development access

The `stop.sh` script tears down the Kind cluster, removing all resources.
