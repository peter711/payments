#!/usr/bin/env bash
# Synthetic logs demo: drives a mixed workload through the payment API so the
# Loki/Grafana log dashboard has data, then prints ready-to-use LogQL queries.
#
# Usage:
#   scripts/k8s/80-logs-demo.sh
#
#   # against the full docker-compose stack instead of k3d
#   API_URL=http://localhost:18080 scripts/k8s/80-logs-demo.sh
#
# Tunables (all optional env vars):
#   API_URL         target base URL (default http://payments.localhost:8081)
#   ROUNDS          number of mixed rounds  (default 3)
#   BATCH           normal payments/round   (default 5)
#   SLEEP_BETWEEN   seconds between rounds  (default 1)
#
# The correlation id of a request defaults to its paymentId when no
# X-Correlation-Id header is sent, so the demo payment id doubles as the
# correlation id used in the sample queries below.
set -euo pipefail

api_url="${API_URL:-http://payments.localhost:8081}"
rounds="${ROUNDS:-3}"
batch="${BATCH:-5}"
sleep_between="${SLEEP_BETWEEN:-1}"
run_id="logs-$(date +%s)-$$"

body() {
  # paymentId accountId amount [currency]
  printf '{"paymentId":"%s","accountId":"%s","amount":%s,"currency":"%s"}' \
    "$1" "$2" "$3" "${4:-PLN}"
}

post() {
  curl -s -o /dev/null -w '%{http_code}' \
    -X POST "$api_url/payments" -H 'Content-Type: application/json' \
    -d "$1" 2>/dev/null || echo 000
}

echo "Logs demo -> $api_url  (run id: $run_id)"
echo

for round in $(seq 1 "$rounds"); do
  for i in $(seq 1 "$batch"); do
    post "$(body "$run_id-n-$round-$i" "ACC-DEMO" 10)" >/dev/null
  done

  # idempotency: same paymentId twice -> one charge, one duplicate skip
  post "$(body "$run_id-dup-$round" "ACC-DEMO" 25)" >/dev/null
  post "$(body "$run_id-dup-$round" "ACC-DEMO" 25)" >/dev/null

  # invalid payload -> straight to payments.requested.DLT
  post "$(body "$run_id-invalid-$round" "ACC-DEMO" -1)" >/dev/null

  # always-timeout account -> retried, then dead-lettered
  post "$(body "$run_id-timeout-$round" "ACC-TIMEOUT" 10)" >/dev/null

  # flaky account -> times out twice, then succeeds
  post "$(body "$run_id-flaky-$round" "ACC-FLAKY" 30)" >/dev/null

  echo "round $round/$rounds done"
  sleep "$sleep_between"
done

demo_payment="$run_id-n-1-1"

echo
echo "waiting for the pipeline to drain..."
sleep 12

cat <<GUIDE

====================== logs ======================
Grafana : http://grafana.localhost:8081  ->  Explore -> Loki
          (or the "Payments Logs" dashboard)

One payment flow, end to end (correlation_id == paymentId here):
  {namespace="payments"} | json | correlation_id="$demo_payment"

Everything a single service logged:
  {app="payment-processor"}

Errors only:
  {namespace="payments", level="error"}

Dead-lettered / retried payments from this run:
  {app="payment-processor"} | json | payment_id=~"$run_id-.*"

Full context of the timeout flow:
  {namespace="payments"} | json | payment_id=~"$run_id-timeout-.*"

Loki has no ingress; to query it from a shell:
  kubectl -n payments port-forward svc/loki 3100:3100   # http://localhost:3100
GUIDE
