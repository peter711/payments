#!/usr/bin/env bash
# Tails the logs of the application services in the k3d stack. Handy while
# running the curl scenarios in scripts/k8s/curl.
set -euo pipefail

namespace="${NAMESPACE:-payments}"

kubectl -n "$namespace" logs -f \
  -l 'app in (payment-api,payment-processor,notification-service)' \
  --max-log-requests=10
