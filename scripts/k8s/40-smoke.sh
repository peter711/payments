#!/usr/bin/env bash
# Sends a couple of payments through the ingress to smoke-test the full stack.
# Override the target with API_URL.
set -euo pipefail

api_url="${API_URL:-http://payments.localhost:8081}"

curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' \
  -d '{"accountId":"ACC-1","amount":10,"currency":"PLN"}'; echo
curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' \
  -d '{"accountId":"ACC-2","amount":20,"currency":"PLN"}'; echo

echo "Kafka UI: http://kafka-ui.localhost:8081"
