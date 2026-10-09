#!/usr/bin/env bash
# Gateway-timeout scenario.
# ACC-TIMEOUT always times out, so the record is retried by the error handler and
# finally routed to payments.requested.DLT (it is never charged).
set -euo pipefail

payment_id="pay-timeout-$(date +%s)"
curl -s -X POST localhost:8080/payments -H 'Content-Type: application/json' \
  -d '{"paymentId":"'"$payment_id"'","accountId":"ACC-TIMEOUT","amount":10,"currency":"PLN"}'; echo

echo "Sent paymentId=$payment_id (account ACC-TIMEOUT)."
echo "Inspect the dead letter topic with: scripts/kafka/50-kafka-dlt-consumer-start.sh"
