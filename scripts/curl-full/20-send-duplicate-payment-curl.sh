#!/usr/bin/env bash
# Full-stack duplicate scenario: the same paymentId is sent twice. The processor
# must charge the gateway only once and answer the second delivery from its
# stored outcome (a single payments.completed, one notification).
set -euo pipefail

api_url="${API_URL:-http://localhost:18080}"
payment_id="dup-$(date +%s)"
body='{"paymentId":"'"$payment_id"'","accountId":"ACC-DUP","amount":10,"currency":"PLN"}'

echo "Sending paymentId=$payment_id twice..."
curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' -d "$body"; echo
curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' -d "$body"; echo

echo "Expect a single charge in gateway_charge and one notification for $payment_id."
