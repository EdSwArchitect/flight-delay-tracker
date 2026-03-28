#!/usr/bin/env bash
set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS=0
FAIL=0
WARN=0

pass() { echo -e "${GREEN}[PASS]${NC} $1"; ((PASS++)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; ((FAIL++)); }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; ((WARN++)); }

echo "============================================"
echo " Flight Tracker — End-to-End Validation"
echo "============================================"
echo ""

# 1. Cluster health
echo "--- Cluster ---"
if kubectl cluster-info --context kind-flight-tracker &>/dev/null; then
  pass "Kind cluster 'flight-tracker' is reachable"
else
  fail "Kind cluster 'flight-tracker' not reachable"
  echo "Aborting — cluster must be running."
  exit 1
fi

# 2. Namespace check
echo ""
echo "--- Namespaces ---"
for ns in ingestion api ui data observability ingress; do
  if kubectl get namespace "$ns" &>/dev/null; then
    pass "Namespace '$ns' exists"
  else
    fail "Namespace '$ns' missing"
  fi
done

# 3. Pod readiness
echo ""
echo "--- Pods ---"
check_pod() {
  local ns=$1 label=$2 name=$3
  local status
  status=$(kubectl get pods -n "$ns" -l "app=$label" -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
  if [[ "$status" == "Running" ]]; then
    pass "$name pod is Running in $ns"
  else
    fail "$name pod status: $status (namespace: $ns)"
  fi
}

check_pod ingestion opensky-poller "opensky-poller"
check_pod ingestion airlabs-poller "airlabs-poller"
check_pod ingestion join-service "join-service"
check_pod api api "api"
check_pod api ws-server "ws-server"
check_pod ui map-ui "map-ui"

# Check data services
if kubectl get pods -n data -l app.kubernetes.io/name=redis -o jsonpath='{.items[0].status.phase}' 2>/dev/null | grep -q Running; then
  pass "Redis is Running in data"
else
  fail "Redis not Running in data"
fi

if kubectl get pods -n data -l app.kubernetes.io/name=postgresql -o jsonpath='{.items[0].status.phase}' 2>/dev/null | grep -q Running; then
  pass "Postgres is Running in data"
else
  fail "Postgres not Running in data"
fi

# 4. Redis data
echo ""
echo "--- Redis ---"
POSITION_KEYS=$(kubectl exec -n data deploy/redis-master -- redis-cli KEYS 'flight:position:*' 2>/dev/null | wc -l | tr -d ' ')
if [[ "$POSITION_KEYS" -gt 0 ]]; then
  pass "Redis has $POSITION_KEYS flight:position keys"
else
  warn "Redis has 0 flight:position keys (OpenSky may not have polled yet)"
fi

ENRICHED_KEYS=$(kubectl exec -n data deploy/redis-master -- redis-cli KEYS 'flight:enriched:*' 2>/dev/null | wc -l | tr -d ' ')
if [[ "$ENRICHED_KEYS" -gt 0 ]]; then
  pass "Redis has $ENRICHED_KEYS flight:enriched keys"
else
  warn "Redis has 0 flight:enriched keys (join service may not have run yet)"
fi

INDEX_SIZE=$(kubectl exec -n data deploy/redis-master -- redis-cli HLEN callsign:index 2>/dev/null || echo "0")
if [[ "$INDEX_SIZE" -gt 0 ]]; then
  pass "Callsign index has $INDEX_SIZE entries"
else
  warn "Callsign index is empty (AirLabs may not have polled yet)"
fi

# 5. Postgres data
echo ""
echo "--- Postgres ---"
SCHEDULE_COUNT=$(kubectl exec -n data deploy/postgresql -- bash -c "PGPASSWORD=flightpass psql -U flightuser -d flightdb -t -c 'SELECT count(*) FROM flight_schedules'" 2>/dev/null | tr -d ' ' || echo "0")
if [[ "$SCHEDULE_COUNT" -gt 0 ]]; then
  pass "flight_schedules has $SCHEDULE_COUNT rows"
else
  warn "flight_schedules is empty"
fi

RAW_COUNT=$(kubectl exec -n data deploy/postgresql -- bash -c "PGPASSWORD=flightpass psql -U flightuser -d flightdb -t -c 'SELECT count(*) FROM raw_api_responses'" 2>/dev/null | tr -d ' ' || echo "0")
if [[ "$RAW_COUNT" -gt 0 ]]; then
  pass "raw_api_responses has $RAW_COUNT rows"
else
  warn "raw_api_responses is empty"
fi

# 6. HTTP endpoints
echo ""
echo "--- HTTP Endpoints ---"

check_http() {
  local url=$1 name=$2
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$url" 2>/dev/null || echo "000")
  if [[ "$code" == "200" ]]; then
    pass "$name returned HTTP 200"
  else
    fail "$name returned HTTP $code"
  fi
}

check_http "http://localhost:8080" "Map UI"
check_http "http://localhost:8080/api/flights" "REST API /flights"
check_http "http://localhost:8080/api/health" "REST API /health"
check_http "http://localhost:8080/grafana/api/health" "Grafana"

# 7. Metrics endpoints (via port-forward or pod exec)
echo ""
echo "--- Metrics ---"
for svc in opensky-poller:8081:ingestion airlabs-poller:8082:ingestion join-service:8083:ingestion; do
  IFS=: read -r name port ns <<< "$svc"
  if kubectl exec -n "$ns" deploy/"$name" -- wget -qO- "http://localhost:$port/metrics" 2>/dev/null | grep -q "# HELP"; then
    pass "$name /metrics is serving Prometheus metrics"
  else
    warn "$name /metrics not reachable (may need wget/curl in container)"
  fi
done

# 8. Ingress
echo ""
echo "--- Ingress ---"
if kubectl get ingress -A 2>/dev/null | grep -q flight-tracker; then
  pass "Ingress resource 'flight-tracker-ingress' found"
else
  fail "Ingress resource not found"
fi

# Summary
echo ""
echo "============================================"
echo " Results: ${GREEN}${PASS} passed${NC}, ${RED}${FAIL} failed${NC}, ${YELLOW}${WARN} warnings${NC}"
echo "============================================"

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
