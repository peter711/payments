#!/usr/bin/env bash
# Full-stack variant of scripts/curl/80: signs a Stripe webhook locally and
# POSTs it to the containerised payment-api (docker-compose.full.yml, port
# 18080), reproducing the reconciliation flow with no Stripe account.
#
# The webhook host may run a different provider than the processor, so the API
# can expose /webhooks/stripe while payments still go through the mock gateway.
# In .env (or exported) set:
#
#   PAYMENT_API_GATEWAY_PROVIDER=stripe   # expose the webhook endpoint on payment-api
#   PAYMENT_GATEWAY_PROVIDER=mock         # keep the processor on the mock gateway
#   STRIPE_API_KEY=sk_test_dummy          # placeholder; the API never charges
#   STRIPE_WEBHOOK_SECRET=whsec_local     # any whsec_... value
#
# Then: docker compose -f docker-compose.full.yml up --build
#
# Usage:
#   ./80-send-stripe-webhook-curl.sh <paymentId> [status] [accountId]
#
#   status   payment_intent.succeeded (default) | payment_intent.payment_failed | payment_intent.canceled
#
# Environment overrides:
#   API_URL               payment-api base URL (default http://localhost:18080)
#   STRIPE_WEBHOOK_SECRET signing secret (default whsec_local, must match the API)
#   VERIFY=1              wait for and print the outcome from the full-stack Kafka
set -euo pipefail

api_url="${API_URL:-http://localhost:18080}"
secret="${STRIPE_WEBHOOK_SECRET:-whsec_local}"
payment_id="${1:?usage: $0 <paymentId> [status] [accountId]}"
status="${2:-payment_intent.succeeded}"
account_id="${3:-ACC-WEBHOOK}"

timestamp="$(date +%s)"
payload='{"id":"evt_local_'"$timestamp"'","object":"event","type":"'"$status"'","created":'"$timestamp"',"data":{"object":{"id":"pi_local_'"$timestamp"'","object":"payment_intent","amount":1234,"currency":"pln","metadata":{"paymentId":"'"$payment_id"'","accountId":"'"$account_id"'"}}}}'

signature="$(printf '%s.%s' "$timestamp" "$payload" | openssl dgst -sha256 -hmac "$secret" -binary | xxd -p -c256)"
header="t=${timestamp},v1=${signature}"

echo "POST ${api_url}/webhooks/stripe  paymentId=${payment_id} status=${status} accountId=${account_id}"
curl -s -o /dev/null -w 'HTTP %{http_code}\n' -X POST "${api_url}/webhooks/stripe" \
  -H 'Content-Type: application/json' \
  -H "Stripe-Signature: ${header}" \
  --data-binary "$payload"

echo
if [[ "${VERIFY:-0}" == "1" ]]; then
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  compose_file="${COMPOSE_FILE:-$script_dir/../../docker-compose.full.yml}"
  echo "Waiting for the outcome of $payment_id on the full-stack Kafka..."
  for topic in payments.provider.events payments.completed payments.corrected; do
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

cat <<EOF
Expected result:
  - one payment.provider_event on payments.provider.events
  - payments.completed  if the payment had no recorded outcome (finalized), or
    payments.corrected  if a recorded FAILED is superseded by this success
  - a matching notification

Watch it yourself:
  scripts/kafka-full/30-kafka-completed-consumer-start.sh   # finalized
  # payments.corrected has no helper yet: consume it directly
  docker compose -f docker-compose.full.yml exec kafka \\
    /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:19092 \\
    --topic payments.corrected --from-beginning

Tip: to see the correction path, make the processor record a FAILED first, e.g.
run it with SPRING_APPLICATION_JSON='{"payment":{"mock":{"success-rate":0}}}'.
EOF
