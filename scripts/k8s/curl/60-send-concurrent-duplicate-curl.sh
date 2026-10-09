#!/usr/bin/env bash
# k8s concurrent duplicate scenario. The SAME paymentId is fired 5x in parallel
# to show that the processor still charges the gateway only once and writes a
# single outbox event (idempotency inbox + unique outbox key), even with two
# processor replicas running.
set -euo pipefail

api_url="${API_URL:-http://payments.localhost:8081}"
namespace="${NAMESPACE:-payments}"
payment_id="race-$(date +%s)"
body='{"paymentId":"'"$payment_id"'","accountId":"ACC-RACE","amount":25,"currency":"PLN"}'

echo "Firing paymentId=$payment_id five times concurrently..."
for _ in 1 2 3 4 5; do
  curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' -d "$body" >/dev/null &
done
wait

echo "Done. Expect exactly one charge and one payments.completed for $payment_id."
echo "Verify once processed with:"
echo "  kubectl -n $namespace exec postgres-0 -- psql -U payments -d payments -c \\"
echo "    \"SELECT (SELECT count(*) FROM gateway_charge WHERE payment_id='$payment_id') AS charges, (SELECT count(*) FROM outbox WHERE payment_id='$payment_id') AS outbox_rows;\""
