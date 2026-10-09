#!/usr/bin/env bash
# k8s variant of scripts/curl-full/10: one flaky payment (retried until the
# unstable gateway recovers) and one invalid payment (routed straight to
# payments.requested.DLT).
set -euo pipefail

api_url="${API_URL:-http://payments.localhost:8081}"

curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' \
  -d '{"paymentId":"pay-flaky","accountId":"ACC-FLAKY","amount":42,"currency":"PLN"}'; echo

curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' \
  -d '{"paymentId":"pay-invalid","accountId":"ACC-1","amount":-100,"currency":"PLN"}'; echo

echo "Inspect the dead letter topic with: scripts/k8s/kafka/40-kafka-dlt-consumer-start.sh"
