#!/usr/bin/env bash
set -euo pipefail

CLUSTER_NAME="flight-tracker"

echo "=== Flight Delay Tracker — Cluster Teardown ==="

if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
  echo "Deleting Kind cluster '${CLUSTER_NAME}'..."
  kind delete cluster --name "$CLUSTER_NAME"
  echo "Cluster deleted."
else
  echo "No cluster named '${CLUSTER_NAME}' found."
fi

echo "=== Teardown complete ==="
