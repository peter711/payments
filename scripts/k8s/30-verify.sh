#!/usr/bin/env bash
# Asserts that every application Deployment runs exactly 2 ready replicas.
set -euo pipefail

namespace="${NAMESPACE:-payments}"
expected="${REPLICAS:-2}"

status=0
for deployment in payment-api payment-processor notification-service; do
  ready="$(kubectl -n "$namespace" get deployment "$deployment" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)"
  ready="${ready:-0}"
  if [[ "$ready" == "$expected" ]]; then
    echo "OK   ${deployment}: ${ready}/${expected} ready"
  else
    echo "FAIL ${deployment}: ${ready}/${expected} ready"
    status=1
  fi
done

exit "$status"
