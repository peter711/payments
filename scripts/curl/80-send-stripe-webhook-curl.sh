#!/usr/bin/env bash
# Reproduces the provider-webhook reconciliation locally, without a Stripe
# account: it signs a Stripe event the same way Stripe does (HMAC-SHA256 over
# "<timestamp>.<payload>" with the whsec_... secret) and POSTs it to
# payment-api's /webhooks/stripe.
#
# Prerequisites:
#   1. payment-api running with the webhook endpoint enabled:
#        PAYMENT_GATEWAY_PROVIDER=stripe
#        STRIPE_API_KEY=sk_test_dummy        # placeholder; the API never charges
#        STRIPE_WEBHOOK_SECRET=whsec_local    # any whsec_... value
#   2. payment-processor running on the mock gateway (default).
#   See docs/operations.md#local-stripe-webhooks.
#
# Usage:
#   ./80-send-stripe-webhook-curl.sh <paymentId> [status] [accountId]
#
#   status   payment_intent.succeeded (default) | payment_intent.payment_failed | payment_intent.canceled
#   account  metadata.accountId stored on the event (default ACC-WEBHOOK)
#
# Environment overrides:
#   API_URL              payment-api base URL (default http://localhost:8080; IDE runs use 9080)
#   STRIPE_WEBHOOK_SECRET signing secret (default whsec_local, must match the API)
set -euo pipefail

api_url="${API_URL:-http://localhost:8080}"
secret="${STRIPE_WEBHOOK_SECRET:-whsec_local}"
payment_id="${1:?usage: $0 <paymentId> [status] [accountId]}"
status="${2:-payment_intent.succeeded}"
account_id="${3:-ACC-WEBHOOK}"

timestamp="$(date +%s)"
payload='{"id":"evt_local_'"$timestamp"'","object":"event","type":"'"$status"'","created":'"$timestamp"',"data":{"object":{"id":"pi_local_'"$timestamp"'","object":"payment_intent","amount":1234,"currency":"pln","metadata":{"paymentId":"'"$payment_id"'","accountId":"'"$account_id"'"}}}}'

# Stripe signs "<timestamp>.<payload>" with HMAC-SHA256 using the signing secret
# and sends the result as "t=<timestamp>,v1=<hex>".
signature="$(printf '%s.%s' "$timestamp" "$payload" | openssl dgst -sha256 -hmac "$secret" -binary | xxd -p -c256)"
header="t=${timestamp},v1=${signature}"

echo "POST ${api_url}/webhooks/stripe  paymentId=${payment_id} status=${status} accountId=${account_id}"
curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X POST "${api_url}/webhooks/stripe" \
  -H 'Content-Type: application/json' \
  -H "Stripe-Signature: ${header}" \
  --data-binary "$payload"

cat <<EOF
Expected result:
  - one payment.provider_event on payments.provider.events
  - payments.completed  if the payment had no recorded outcome (finalized), or
    payments.corrected  if a recorded FAILED is superseded by this success
  - a matching notification

Tip: re-run with a FAILED payment to see the correction path, e.g. start the
processor with SPRING_APPLICATION_JSON='{"payment":{"mock":{"success-rate":0}}}'
so the payment is recorded FAILED first.
EOF
