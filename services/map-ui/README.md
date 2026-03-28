# map-ui

React single-page application that renders a real-time flight map using MapLibre GL JS and Deck.gl, driven by WebSocket data from the ws-server.

## What it does

- Connects to the ws-server via WebSocket at `/ws/positions` and processes `snapshot` and `delta` message envelopes
- Renders aircraft positions as a Deck.gl ScatterplotLayer on a MapLibre GL JS dark basemap
- Color-codes each flight dot by delay severity: grey (unknown), green (on time), amber (1-30 min), orange (31-60 min), red (>60 min)
- Displays a connection status indicator (live flight count or disconnected state)
- Shows a delay color legend overlay
- Opens a FlightDetailPanel on click, fetching full flight details from `GET /api/flights/{icao24}`
- Auto-reconnects the WebSocket after 3 seconds on disconnect

## Delay color tiers

| Delay | Color | RGB |
|---|---|---|
| Unknown (null) | Grey | `[136, 135, 128]` |
| On time (<= 0 min) | Green | `[57, 158, 117]` |
| 1-30 min | Amber | `[239, 159, 39]` |
| 31-60 min | Orange | `[216, 90, 48]` |
| > 60 min | Red | `[226, 75, 74]` |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `VITE_MAPTILER_KEY` | _(empty)_ | MapTiler API key for dark basemap tiles (falls back to MapLibre demo tiles if blank) |

## Ports

| Port | Purpose |
|---|---|
| 5173 | Vite dev server (local development) |
| 80 | Nginx (production Docker image) |

## Dependencies

- **ws-server** -- WebSocket connection at `/ws/positions` for live flight data
- **api** -- REST call to `GET /api/flights/{icao24}` for flight detail panel
- **MapTiler** -- tile server for dark basemap (optional, falls back to demo tiles)

## Key files

| File | Description |
|---|---|
| `src/App.tsx` | Root component: DeckGL + MapLibre map, ScatterplotLayer, connection indicator, click handler |
| `src/main.tsx` | React entry point |
| `src/types.ts` | TypeScript interfaces: EnrichedFlight, FlightPosition, FlightSchedule, FlightDelay, WsMessage |
| `src/hooks/useFlightWebSocket.ts` | WebSocket hook: connect, parse snapshot/delta, auto-reconnect on close |
| `src/components/DelayLegend.tsx` | Delay color legend overlay (5-tier) |
| `src/components/FlightDetailPanel.tsx` | Side panel showing flight details fetched from REST API on click |
| `src/utils/delayColor.ts` | Maps delay minutes to RGB color tuples |
| `vite.config.ts` | Vite config with dev proxy for `/ws` and `/api` |
| `nginx.conf` | Production Nginx config with SPA fallback and `/health` endpoint |
| `package.json` | npm dependencies and scripts |
| `tsconfig.json` | TypeScript compiler configuration |
| `Dockerfile` | Multi-stage build: node:20-alpine (build) + nginx:alpine (serve) |

## Build and run locally

```bash
cd services/map-ui
npm install
npm run dev
```

The Vite dev server proxies `/ws` to `ws://localhost:8086` and `/api` to `http://localhost:8084`.

## Docker

```bash
docker build -t flight-tracker/map-ui services/map-ui
```
