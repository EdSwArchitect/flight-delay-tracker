#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
CLUSTER_NAME="flight-tracker"

echo "=== Flight Delay Tracker — Cluster Bootstrap ==="

# 1. Create Kind cluster
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
  echo "Kind cluster '${CLUSTER_NAME}' already exists, skipping creation."
else
  echo "Creating Kind cluster '${CLUSTER_NAME}'..."
  kind create cluster --name "$CLUSTER_NAME" --config "$PROJECT_DIR/k8s/kind-config.yaml"
fi

kubectl cluster-info --context "kind-${CLUSTER_NAME}"

# 2. Create namespaces
for ns in ingestion api ui data observability ingress; do
  kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f -
done

# 3. Add Helm repos
echo "Adding Helm repositories..."
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx 2>/dev/null || true
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts 2>/dev/null || true
helm repo add grafana https://grafana.github.io/helm-charts 2>/dev/null || true
helm repo update

# 4. Install ingress-nginx
echo "Installing ingress-nginx..."
helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress \
  --values "$PROJECT_DIR/charts/deps/ingress-values.yaml" \
  --wait --timeout 120s

# 5. Install Redis
echo "Installing Redis..."
helm upgrade --install redis oci://registry-1.docker.io/bitnamicharts/redis \
  --namespace data \
  --values "$PROJECT_DIR/charts/deps/redis-values.yaml" \
  --wait --timeout 120s

# 6. Create Postgres init schema ConfigMap (must exist BEFORE Postgres install)
echo "Creating Postgres schema ConfigMap..."
kubectl create configmap postgres-init-schema \
  --namespace data \
  --from-file=schema.sql="$PROJECT_DIR/k8s/schema.sql" \
  --dry-run=client -o yaml | kubectl apply -f -

# 7. Install Postgres (initdb mounts the ConfigMap above)
echo "Installing Postgres..."
helm upgrade --install postgresql oci://registry-1.docker.io/bitnamicharts/postgresql \
  --namespace data \
  --values "$PROJECT_DIR/charts/deps/postgres-values.yaml" \
  --wait --timeout 180s

# 8. Install kube-prometheus-stack
echo "Installing kube-prometheus-stack..."
helm upgrade --install kube-prometheus prometheus-community/kube-prometheus-stack \
  --namespace observability \
  --values "$PROJECT_DIR/charts/deps/kube-prometheus-values.yaml" \
  --wait --timeout 180s || echo "Warning: prometheus install may need retry"

# 9. Install Loki (SingleBinary mode, filesystem storage)
echo "Installing Loki..."
helm upgrade --install loki grafana/loki \
  --namespace observability \
  --values "$PROJECT_DIR/charts/deps/loki-values.yaml" \
  --wait --timeout 180s || echo "Warning: loki install may need retry"

# 10. Install Alloy (log collector, replaces deprecated Promtail)
echo "Installing Alloy..."
helm upgrade --install alloy grafana/alloy \
  --namespace observability \
  --values "$PROJECT_DIR/charts/deps/alloy-values.yaml" \
  --wait --timeout 120s || echo "Warning: alloy install may need retry"

# 11. Build Maven project
echo "Building Maven project..."
cd "$PROJECT_DIR"
mvn clean package -DskipTests -q

# 12. Build Docker images and load into Kind
echo "Building Docker images..."
SERVICES="opensky-poller airlabs-poller join-service api ws-server map-ui"
for svc in $SERVICES; do
  echo "  Building flight-tracker/$svc..."
  docker build -t "flight-tracker/${svc}:latest" "$PROJECT_DIR/services/$svc"
done

echo "Loading images into Kind cluster..."
for svc in $SERVICES; do
  kind load docker-image "flight-tracker/${svc}:latest" --name "$CLUSTER_NAME"
done

# 13. Deploy with Skaffold
echo "Starting Skaffold dev loop..."
skaffold dev --profile=local --port-forward

echo "=== Bootstrap complete ==="
