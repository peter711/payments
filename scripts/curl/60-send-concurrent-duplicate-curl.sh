#!/usr/bin/env bash
# Concurrent duplicate scenario. The SAME paymentId is fired 5x in parallel to
# show that the processor still charges the gateway only once and writes a single
# outbox event (idempotency inbox + unique outbox key).
set -euo pipefail

payment_id="race-$(date +%s)"
body='{"paymentId":"'"$payment_id"'","accountId":"ACC-RACE","amount":25,"currency":"PLN"}'

echo "Firing paymentId=$payment_id five times concurrently..."
for _ in 1 2 3 4 5; do
  curl -s -X POST localhost:8080/payments -H 'Content-Type: application/json' -d "$body" >/dev/null &
done
wait

echo "Done. Expect exactly one charge and one payments.completed for $payment_id."
