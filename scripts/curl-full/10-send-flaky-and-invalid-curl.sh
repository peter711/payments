#!/usr/bin/env bash
# Full-stack variant of scripts/curl/10: one flaky payment (retried until the
# unstable gateway recovers) and one invalid payment (routed straight to
# payments.requested.DLT).
set -euo pipefail

api_url="${API_URL:-http://localhost:18080}"

curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' \
  -d '{"paymentId":"pay-flaky","accountId":"ACC-FLAKY","amount":42,"currency":"PLN"}'; echo

curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' \
  -d '{"paymentId":"pay-invalid","accountId":"ACC-1","amount":-100,"currency":"PLN"}'; echo

echo "Inspect the dead letter topic with: scripts/kafka-full/40-kafka-dlt-consumer-start.sh"
