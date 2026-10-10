#!/usr/bin/env bash
# Exercises the real Stripe gateway through payment-api (services running from
# the IDE against `docker compose up -d`).
#
# The IDE run configurations (.run/) set the ports, so payment-api listens on
# 9080, not 8080. Override with API_URL if you started it differently.
#
# Start payment-processor with the Stripe adapter enabled, e.g. export (or set
# in the run configuration) before launching it:
#
#   export STRIPE_API_KEY=sk_test_...          # from the Stripe dashboard (test mode)
#   export PAYMENT_GATEWAY_PROVIDER=stripe
#
# Every payment is confirmed with payment.stripe.payment-method, which defaults
# to pm_card_visa and therefore succeeds. To see a decline, restart the
# processor with STRIPE_PAYMENT_METHOD=pm_card_visa_chargeDeclined.
set -euo pipefail

api_url="${API_URL:-http://localhost:9080}"
payment_id="stripe-$(date +%s)"
amount="${AMOUNT:-12.34}"
currency="${CURRENCY:-PLN}"
body='{"paymentId":"'"$payment_id"'","accountId":"ACC-STRIPE","amount":'"$amount"',"currency":"'"$currency"'"}'

echo "Sending a Stripe payment (paymentId=$payment_id, $amount $currency) to $api_url..."
curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' -d "$body"; echo

echo
echo "Re-sending the same paymentId to show provider idempotency: Stripe returns"
echo "the original PaymentIntent and the processor skips it as a duplicate."
curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' -d "$body"; echo

echo
echo "Watch the outcome, e.g.:"
echo "  docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh \\"
echo "    --bootstrap-server localhost:9092 --topic payments.completed --from-beginning"
echo "Or open the Stripe dashboard (test mode) and find the PaymentIntent whose"
echo "metadata.paymentId is $payment_id."
echo
echo "If you see random declines (~20%) the mock gateway is still active: make"
echo "sure PAYMENT_GATEWAY_PROVIDER=stripe is exported before starting the service."
