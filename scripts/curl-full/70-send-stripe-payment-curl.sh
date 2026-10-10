#!/usr/bin/env bash
# Exercises the real Stripe gateway through payment-api.
#
# The stack must run with the Stripe adapter enabled, for example:
#
#   export STRIPE_API_KEY=sk_test_...          # from the Stripe dashboard (test mode)
#   export PAYMENT_GATEWAY_PROVIDER=stripe
#   docker compose -f docker-compose.full.yml up --build
#
# (or set both variables in .env). Every payment is confirmed with
# payment.stripe.payment-method, which defaults to pm_card_visa and therefore
# succeeds. To see a decline, restart the processor with
# STRIPE_PAYMENT_METHOD=pm_card_visa_chargeDeclined.
#
# Override the API target with API_URL (e.g. API_URL=http://localhost:8080 when
# running the services from the IDE). Set VERIFY=1 to wait for and print the
# outcome from the full-stack Kafka.
set -euo pipefail

api_url="${API_URL:-http://localhost:18080}"
payment_id="stripe-$(date +%s)"
amount="${AMOUNT:-12.34}"
currency="${CURRENCY:-PLN}"
body='{"paymentId":"'"$payment_id"'","accountId":"ACC-STRIPE","amount":'"$amount"',"currency":"'"$currency"'"}'

echo "Sending a Stripe payment (paymentId=$payment_id, $amount $currency)..."
curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' -d "$body"; echo

echo
echo "Re-sending the same paymentId to show provider idempotency: Stripe returns"
echo "the original PaymentIntent and the processor skips it as a duplicate."
curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' -d "$body"; echo

echo
if [[ "${VERIFY:-0}" == "1" ]]; then
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  compose_file="${COMPOSE_FILE:-$script_dir/../../docker-compose.full.yml}"
  echo "Waiting for the outcome of $payment_id on the full-stack Kafka..."
  for topic in payments.completed payments.failed; do
    match=$(docker compose -f "$compose_file" exec -T kafka \
      /opt/kafka/bin/kafka-console-consumer.sh \
      --bootstrap-server localhost:19092 --topic "$topic" \
      --from-beginning --timeout-ms 8000 --property print.key=true 2>/dev/null \
      | grep -F "$payment_id" | head -n1 || true)
    if [[ -n "$match" ]]; then
      echo "  $topic -> $match"
    fi
  done
  echo
fi

echo "Watch the outcome yourself:"
echo "  scripts/kafka-full/30-kafka-completed-consumer-start.sh   # success"
echo "  scripts/kafka-full/35-kafka-failed-consumer-start.sh      # decline"
echo "Or open the Stripe dashboard (test mode) and find the PaymentIntent whose"
echo "metadata.paymentId is $payment_id."
echo
echo "If you see random declines (~20%) the mock gateway is still active: make"
echo "sure PAYMENT_GATEWAY_PROVIDER=stripe is exported (or set in .env)."
