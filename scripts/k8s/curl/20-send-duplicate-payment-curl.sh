#!/usr/bin/env bash
# k8s duplicate scenario: the same paymentId is sent twice. The processor must
# charge the gateway only once and answer the second delivery from its stored
# outcome (a single payments.completed, one notification). Unlike the compose
# variant, the state lives in the shared PostgreSQL, so it can be verified
# directly after the run.
set -euo pipefail

api_url="${API_URL:-http://payments.localhost:8081}"
namespace="${NAMESPACE:-payments}"
payment_id="dup-$(date +%s)"
body='{"paymentId":"'"$payment_id"'","accountId":"ACC-DUP","amount":10,"currency":"PLN"}'

echo "Sending paymentId=$payment_id twice..."
curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' -d "$body"; echo
curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' -d "$body"; echo

echo "Expect a single charge in gateway_charge and one notification for $payment_id."
echo "Verify once processed with:"
echo "  kubectl -n $namespace exec postgres-0 -- psql -U payments -d payments -c \\"
echo "    \"SELECT (SELECT count(*) FROM gateway_charge WHERE payment_id='$payment_id') AS charges, (SELECT count(*) FROM outbox WHERE payment_id='$payment_id') AS outbox_rows;\""
