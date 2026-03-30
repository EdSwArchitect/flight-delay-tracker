# Prompts Log

Prompts used with Claude Code during development of the Flight Delay Tracker prototype. Organized by category with the resulting changes noted.

---

## Secret Management

### Auto-create AirLabs secret from file

> the file airlabs-key.txt contains the AIRLABS_API_KEY. Create as part of the start script, reading that file and creating the airlabs-credentials. The file airlabs-key.txt is in .gitignore and you can verify the .gitignore before doing the update

**Result:** Updated `scripts/start.sh` to read `airlabs-key.txt` and create the `airlabs-credentials` Kubernetes secret automatically using `kubectl create secret --dry-run=client | kubectl apply`. Idempotent on re-runs, warns if file is missing.

### Fix secret namespace mismatch

> same error but the secret is created

**Context:** Pod was failing with `secret "airlabs-credentials" not found` despite the secret existing. Investigation revealed Skaffold deploys to the `default` namespace, not `ingestion`.

**Result:** Identified `skaffold.yaml` line 43 (`namespace: default`) as the root cause. Updated `start.sh` to create the secret in `default`. Updated the airlabs-poller Helm deployment template to use `secretKeyRef` instead of `envFrom`/`secretRef` to correctly map secret key `api-key` to env var `AIRLABS_API_KEY`.

---

## Bug Fixes

### Fix AirLabs poller errors

> 01:20:03.973 [virtual-32] WARN c.b.f.airlabs.AirLabsPollerApp - Upsert failed for NZ9035: ERROR: null value in column "dep_time" of relation "flight_schedules" violates not-null constraint
>
> 01:20:07.647 [virtual-34] WARN c.b.f.airlabs.AirLabsPollerApp - AirLabs API error: "type" is required (departures or arrivals)

**Result:** Two fixes in `AirLabsPollerApp.java`:
1. Added `parseTimestamp(depTime) == null` guard to skip entries with present but unparseable `dep_time` values.
2. Added `type=departures` parameter to the `/delays` API URL.

---

## Rate Limiting

### Add exponential backoff for OpenSky

> for opensky do exponential backoff

**Context:** OpenSky API returning HTTP 429 (rate limited) at 15-second poll interval.

**Result:** Rewrote `OpenSkyPollerApp.java` polling from `scheduleAtFixedRate` to single-shot `schedule()` calls with dynamic delay. On 429 or failure: doubles delay (30s -> 60s -> 120s -> 240s -> 300s cap). On success: resets to base interval. Also increased default `OPENSKY_POLL_INTERVAL_SEC` from 15 to 30 in Helm values.

### Slow down OpenSky poll rate

> opensky-poller OpenSky API returned status 429. slow down the rate request

**Result:** Changed `OPENSKY_POLL_INTERVAL_SEC` from `"15"` to `"30"` in `charts/flight-tracker/values.yaml`.

---

## Documentation

### Update all READMEs

> update all readmes as required

**Result:** Updated 3 README files:
- `README.md` (root): AirLabs secret auto-creation, namespace corrections, poll interval update.
- `services/opensky-poller/README.md`: Poll interval default to 30s, exponential backoff documentation.
- `services/airlabs-poller/README.md`: dep_time null handling, auto-secret creation from file.

### Update CLAUDE.md

> update CLAUDE.md

**Result:** Updated 7 sections: project description, repository layout, technology decisions, Kubernetes namespaces, secrets documentation, development workflow log commands, and added 7 new entries to the notes/decisions log.

### Create architecture decision records

> create doc/decisions directory and make a decisions.md readme file

**Result:** Created `docs/decisions/decisions.md` with 12 ADRs covering all key technical decisions from the project.

---

## Exploration

### Query service endpoints

> what are the GET endpoints for service/api

**Result:** Identified 4 GET endpoints in `FlightApiApp.java`: `/health`, `/flights`, `/flights/{icao24}`, `/delays` on port 8084, plus `/metrics` on port 8085.

### How to add a secret

> how can a secret be added for the AirLabs api key

**Result:** Provided the `kubectl create secret` command for creating the `airlabs-credentials` secret.

### How to redeploy

> how do i redeploy

**Result:** Provided `skaffold dev --profile=local --port-forward` command for redeploying after changes.

---

## UI Enhancements

### Add flight info popup on click

> update map ui so that when the user clicks on a dot for a flight, it pops up flight information available

**Result:** Replaced the fixed-position `FlightDetailPanel` (which fetched from the REST API) with a new `FlightPopup` component that:
- Positions at the click location next to the flight dot (flips left if near viewport edge)
- Uses locally cached WebSocket data instead of an API call (instant, no network round-trip)
- Shows: flight IATA / callsign header, route bar (DEP --- ARR), color-coded delay badge, 2-column grid with altitude (ft), speed (kts), heading, departure/arrival times, estimated times, and resolution type
- Border color matches the flight's delay tier for visual association
- Dismisses on map click or close button

---

## Bug Fixes (cont.)

### Fix AirLabs timestamp parsing (callsign:index empty)

> what is in redis / why is it missing

**Context:** Redis had ~8k `flight:position:*` and ~8k `flight:enriched:*` keys, but `callsign:index` was missing. All enriched flights were `position_only` — no schedule resolution. Logs showed `Polled 0 schedules, indexed 0 callsigns` despite AirLabs returning 139 schedules in a 93KB response.

**Root cause:** AirLabs returns timestamps like `"2026-03-29 17:40"` (no seconds). `parseTimestamp()` tried `ISO_OFFSET_DATE_TIME` (needs timezone offset — fails) then `Timestamp.valueOf()` (needs seconds — fails). Every schedule entry was skipped at the `parseTimestamp(depTime) == null` guard.

**Result:** Added a third fallback in `parseTimestamp()` using `LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))`. Schedule ingestion and callsign index now populate correctly.

---

## Documentation (cont.)

### Update READMEs, CLAUDE.md, and prompts

> update necessary readmes / update CLAUDE.md / add prompts to prompts doc

**Result:** Updated 4 files:
- `README.md` (root): Fixed namespace references from per-service to `default`, added click-to-inspect mention, corrected `kubectl logs` namespaces.
- `services/map-ui/README.md`: Updated click behavior description, dependency notes, and key files table for FlightPopup.
- `services/airlabs-poller/README.md`: Added multi-format timestamp parsing note.
- `CLAUDE.md`: Updated map-ui click behavior, added 3 decision log entries for timestamp fix, callsign:index root cause, and popup replacement.
- `docs/prompts/prompts.md`: Added entries for flight popup, timestamp parsing bug, and documentation updates.
