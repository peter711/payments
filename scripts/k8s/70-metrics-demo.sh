#!/usr/bin/env bash
# Synthetic metrics demo: drives a mixed workload through the payment API so the
# Prometheus/Grafana dashboards have something to show.
#
# It is intentionally chatty and exercises every business meter:
#   accepted requests / publish rate  -> payments_api_requests_total
#   processing outcomes (ok/decline)  -> payments_processor_outcomes_total
#   idempotency duplicates            -> payments_processor_duplicates_total
#   gateway latency                   -> payments_processor_gateway_duration_seconds
#   outbox backlog + publish rate     -> payments_processor_outbox_pending / _published_total
#   dead letters (invalid + timeout)  -> payments_processor_dlq_total
#   notifications + dedup             -> notifications_sent_total / _duplicates_total
#   consumer lag (burst)              -> kafka_consumer_fetch_manager_records_lag_max
#
# Usage:
#   scripts/k8s/70-metrics-demo.sh
#
#   # against the full docker-compose stack instead of k3d
#   API_URL=http://localhost:18080 scripts/k8s/70-metrics-demo.sh
#
# Tunables (all optional env vars):
#   API_URL         target base URL (default http://payments.localhost:8081)
#   ROUNDS          number of mixed rounds       (default 8)
#   BATCH           normal payments per round    (default 10)
#   PARALLEL        in-flight requests per batch (default 5)
#   BURST           final burst size             (default 120)
#   SLEEP_BETWEEN   seconds between rounds       (default 1)
#
# The mock gateway accepts ~80% of ACC-DEMO payments; ACC-TIMEOUT always times
# out (-> DLT) and ACC-FLAKY times out twice then succeeds (-> retries).
set -euo pipefail

api_url="${API_URL:-http://payments.localhost:8081}"
rounds="${ROUNDS:-8}"
batch="${BATCH:-10}"
parallel="${PARALLEL:-5}"
burst="${BURST:-120}"
sleep_between="${SLEEP_BETWEEN:-1}"
run_id="demo-$(date +%s)-$$"

results="$(mktemp)"
trap 'rm -f "$results"' EXIT

# POSTs one payment and records exactly one HTTP status line. Safe to call in the
# background (the temp file is opened in append mode).
send() {
  local body="$1" code
  code="$(curl -s -o /dev/null -w '%{http_code}' \
    -X POST "$api_url/payments" -H 'Content-Type: application/json' \
    -d "$body" 2>/dev/null)" || code="000"
  printf '%s\n' "${code:-000}" >>"$results"
}

body() {
  # paymentId accountId amount [currency]
  printf '{"paymentId":"%s","accountId":"%s","amount":%s,"currency":"%s"}' \
    "$1" "$2" "$3" "${4:-PLN}"
}

# Fires `total` requests with at most `conc` in flight, then returns.
send_burst() {
  local total="$1" conc="$2" i=0 j
  while (( i < total )); do
    for (( j = 0; j < conc && i < total; j++, i++ )); do
      send "$(body "$run_id-burst-$i" "ACC-DEMO" 5)" &
    done
    wait
  done
}

echo "Metrics demo -> $api_url  (run id: $run_id)"
echo "rounds=$rounds batch=$batch parallel=$parallel burst=$burst"
echo

for round in $(seq 1 "$rounds"); do
  # 1) normal traffic: ~80% success, ~20% declined by the mock gateway.
  # j is (re)initialised by the for so a batch that is not a multiple of
  # `parallel` cannot spin without making progress.
  i=0
  while (( i < batch )); do
    for (( j = 0; j < parallel && i < batch; j++, i++ )); do
      send "$(body "$run_id-n-$round-$i" "ACC-DEMO" 10)" &
    done
    wait
  done

  # 2) idempotency: same paymentId twice -> one charge, one duplicate skip
  dup_id="$run_id-dup-$round"
  send "$(body "$dup_id" "ACC-DEMO" 25)"
  send "$(body "$dup_id" "ACC-DEMO" 25)"

  # 3) invalid payload -> straight to payments.requested.DLT
  send "$(body "$run_id-invalid-$round" "ACC-DEMO" -1)"

  # 4) always-timeout account -> retried, then dead-lettered
  send "$(body "$run_id-timeout-$round" "ACC-TIMEOUT" 10)"

  # 5) flaky account -> times out twice, then succeeds
  send "$(body "$run_id-flaky-$round" "ACC-FLAKY" 30)"

  echo "round $round/$rounds done ($(wc -l <"$results" | tr -d ' ') requests so far)"
  sleep "$sleep_between"
done

echo
echo "final burst: $burst requests, $parallel in flight"
send_burst "$burst" "$parallel"

# Let the pipeline drain: gateway delay (~200ms) + outbox poll (~500ms) + retries.
echo
echo "waiting for the pipeline to drain..."
sleep 12

total="$(wc -l <"$results" | tr -d ' ')"
ok="$(grep -c '^2' "$results" || true)"
other="$(grep -vc '^2' "$results" || true)"

echo
echo "==================== summary ===================="
echo "requests sent : $total"
echo "HTTP 2xx      : $ok"
echo "non-2xx       : $other"
echo
echo "Grafana   : http://grafana.localhost:8081   (folder 'Payments')"
echo "Prometheus: kubectl -n payments port-forward svc/prometheus 9090:9090"
echo "            -> http://localhost:9090  (check /targets and /alerts)"
echo
echo "Handy PromQL:"
cat <<'PROMQL'
  accepted req rate   sum(rate(payments_api_requests_total[1m]))
  outcomes by result  sum by (result) (rate(payments_processor_outcomes_total[5m]))
  gateway p95         histogram_quantile(0.95, sum by (le) (rate(payments_processor_gateway_duration_seconds_bucket[5m])))
  outbox backlog      max(payments_processor_outbox_pending)
  DLQ (10m)           sum(increase(payments_processor_dlq_total[10m]))
  duplicates          sum(increase(payments_processor_duplicates_total[10m]))
  notifications       sum by (result) (rate(notifications_sent_total[5m]))
  consumer lag        max by (application) (kafka_consumer_fetch_manager_records_lag_max)
PROMQL

# Best-effort live snapshot straight from the API (the ingress exposes /actuator).
snapshot="$(curl -s "$api_url/actuator/prometheus" 2>/dev/null || true)"
if [[ -n "$snapshot" ]]; then
  echo
  echo "payment-api /actuator/prometheus snapshot:"
  echo "$snapshot" | grep -E '^payments_api_requests_total' || true
fi
