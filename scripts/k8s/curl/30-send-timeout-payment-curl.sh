#!/usr/bin/env bash
# k8s gateway-timeout scenario.
# ACC-TIMEOUT always times out, so the record is retried by the error handler and
# finally routed to payments.requested.DLT (it is never charged).
set -euo pipefail

api_url="${API_URL:-http://payments.localhost:8081}"
payment_id="pay-timeout-$(date +%s)"
curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' \
  -d '{"paymentId":"'"$payment_id"'","accountId":"ACC-TIMEOUT","amount":10,"currency":"PLN"}'; echo

echo "Sent paymentId=$payment_id (account ACC-TIMEOUT)."
echo "Inspect the dead letter topic with: scripts/k8s/kafka/40-kafka-dlt-consumer-start.sh"
