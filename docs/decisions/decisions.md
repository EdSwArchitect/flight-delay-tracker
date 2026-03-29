# Architecture Decision Records

This directory captures key technical decisions made during the Flight Delay Tracker prototype build. Each entry records what was decided, why, and what alternatives were considered.

---

## ADR-001: Java 21 over Java 25

**Date:** 2026-03-28
**Status:** Accepted

**Context:** The project initially targeted Java 25, but virtual threads and records are fully supported in Java 21 LTS (Corretto via SDKMAN).

**Decision:** Downgrade to Java 21. Removed `--enable-preview` from the compiler and all Dockerfiles.

**Rationale:** Java 21 is the current LTS release with broad toolchain and container image support. No preview features are needed.

---

## ADR-002: Javalin 6 over Spring Boot

**Date:** 2026-03-28
**Status:** Accepted

**Context:** Needed a lightweight REST/WebSocket framework compatible with virtual threads for fast Skaffold dev loops.

**Decision:** Use Javalin 6 with virtual threads across all Java services. No Spring Boot.

**Rationale:** Minimal framework overhead, explicit wiring, sub-second container startup. Spring Boot's auto-configuration and classpath scanning add startup latency that slows the inner dev loop.

---

## ADR-003: Callsign normalisation for join strategy

**Date:** 2026-03-28
**Status:** Accepted

**Context:** OpenSky provides `icao24` + `callsign`; AirLabs provides `flight_iata`. There is no shared key between the two APIs.

**Decision:** 3-step callsign resolution chain: exact match, ICAO-to-IATA prefix normalisation (10 airline mappings), fallback to PositionOnlyFlight or UnresolvableFlight.

**Rationale:** Callsign is the closest common identifier. The ICAO prefix mapping covers major US carriers (AAL, DAL, UAL, SWA, etc.). Logic extracted into `CallsignResolver.java` for unit testability.

**Alternatives considered:** ICAO24-to-registration lookup (requires external database), ADS-B Exchange API (paid).

---

## ADR-004: Postgres JSONB for raw API archival

**Date:** 2026-03-28
**Status:** Accepted

**Context:** Needed to archive raw API responses for debugging and replay. MinIO community edition was archived in 2026.

**Decision:** Store raw responses in a Postgres `raw_api_responses` table with a JSONB `payload` column.

**Rationale:** Avoids adding another infrastructure dependency. Sufficient for prototype volumes. SeaweedFS deferred to production.

---

## ADR-005: Exponential backoff for OpenSky polling

**Date:** 2026-03-28
**Status:** Accepted

**Context:** OpenSky's free tier has strict rate limits. Polling at 15-second intervals consistently triggered HTTP 429 responses.

**Decision:** Replaced fixed-rate scheduling with single-shot `schedule()` calls. On 429 or failure, the delay doubles (30s -> 60s -> 120s -> 240s -> 300s cap). On success, resets to the base interval.

**Rationale:** Prevents sustained rate-limit violations while recovering automatically when the API becomes available. Base interval increased from 15s to 30s for additional headroom.

**Alternatives considered:** Fixed longer interval (simple but wastes capacity when the API is healthy), circuit breaker library (overkill for a single HTTP call).

---

## ADR-006: AirLabs secret auto-creation from file

**Date:** 2026-03-28
**Status:** Accepted

**Context:** The `airlabs-credentials` Kubernetes secret was created manually, which was error-prone (wrong namespace, forgotten step).

**Decision:** `start.sh` reads `airlabs-key.txt` from the project root and creates the secret automatically using `kubectl create secret --dry-run=client | kubectl apply`. The file is in `.gitignore`.

**Rationale:** Reduces manual setup steps. The `--dry-run=client | apply` pattern is idempotent on re-runs. The key file never enters version control.

---

## ADR-007: Default namespace for Skaffold deployments

**Date:** 2026-03-28
**Status:** Accepted

**Context:** CLAUDE.md and Helm chart templates originally assumed per-service namespaces (`ingestion`, `api`, `ui`), but `skaffold.yaml` deploys the umbrella Helm release to the `default` namespace.

**Decision:** Accept `default` namespace for all application services via Skaffold. Infrastructure dependencies (Redis, Postgres, Prometheus, Loki, ingress-nginx) remain in their dedicated namespaces (`data`, `observability`, `ingress`).

**Rationale:** A single Helm release cannot span multiple namespaces without subcharts overriding their namespace individually. For a local prototype, one namespace for app services is simpler. Secrets must be co-located with the pods that reference them.

---

## ADR-008: secretKeyRef over envFrom for API key injection

**Date:** 2026-03-28
**Status:** Accepted

**Context:** The airlabs-poller deployment template used `envFrom` with `secretRef`, which injects secret keys using their literal names (e.g., `api-key`). The application expects `AIRLABS_API_KEY`.

**Decision:** Switched to explicit `env` entries with `valueFrom.secretKeyRef` to map the secret's `api-key` field to the `AIRLABS_API_KEY` environment variable.

**Rationale:** Decouples the Kubernetes secret key naming from the application's expected environment variable name. More explicit and debuggable than `envFrom`.

---

## ADR-009: AirLabs delays endpoint requires type parameter

**Date:** 2026-03-28
**Status:** Accepted

**Context:** The AirLabs `/delays` endpoint returned an error: `"type" is required (departures or arrivals)`.

**Decision:** Added `type=departures` to the delays API URL.

**Rationale:** The AirLabs API requires an explicit direction filter. Since the poller is scoped to a departure airport (`DEP_AIRPORT`), `type=departures` is the correct value.

---

## ADR-010: Skip schedule entries with unparseable dep_time

**Date:** 2026-03-28
**Status:** Accepted

**Context:** Some AirLabs schedule entries have a `dep_time` value that is present but unparseable, causing a Postgres NOT NULL constraint violation on insert.

**Decision:** Added a `parseTimestamp(depTime) == null` guard before the upsert, skipping entries where the timestamp cannot be parsed.

**Rationale:** The `dep_time` column is part of the primary key and cannot be null. Skipping unparseable entries is safer than inserting garbage data or failing the entire poll batch.

---

## ADR-011: Grafana ingress via ExternalName service

**Date:** 2026-03-28
**Status:** Accepted

**Context:** Grafana runs in the `observability` namespace but needs to be accessible via the ingress in the `ingress` namespace at `/grafana`.

**Decision:** Created an ExternalName service (`grafana-proxy`) for cross-namespace routing.

**Rationale:** Nginx ingress backends must be in the same namespace as the Ingress resource, or use ExternalName to proxy across namespaces. `GF_SERVER_ROOT_URL` must be set to `/grafana` for sub-path serving.

---

## ADR-012: Loki SingleBinary + Alloy over deprecated loki-stack

**Date:** 2026-03-28
**Status:** Accepted

**Context:** The `grafana/loki-stack` Helm chart (which bundled Promtail) was deprecated.

**Decision:** Replaced with `grafana/loki` (SingleBinary mode, filesystem storage) and `grafana/alloy` as the log collector.

**Rationale:** Alloy is Grafana's recommended replacement for Promtail. SingleBinary mode is appropriate for local/prototype deployments.
