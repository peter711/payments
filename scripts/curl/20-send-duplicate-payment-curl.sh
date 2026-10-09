#!/usr/bin/env bash
# Demonstrates double-submit/redelivery protection: the same paymentId is sent
# twice. The processor must charge the gateway only once and answer the second
# delivery from its stored outcome.
set -euo pipefail

payment_id="dup-$(date +%s)"
body='{"paymentId":"'"$payment_id"'","accountId":"ACC-DUP","amount":10,"currency":"PLN"}'

echo "Sending paymentId=$payment_id twice..."
curl -s -X POST localhost:8080/payments -H 'Content-Type: application/json' -d "$body"; echo
curl -s -X POST localhost:8080/payments -H 'Content-Type: application/json' -d "$body"; echo

echo "Expect a single charge in gateway_charge and one notification for $payment_id."
