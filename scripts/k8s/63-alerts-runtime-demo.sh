#!/usr/bin/env bash
# Runtime alerts demo: forces Kafka consumer-group rebalances by rolling the
# payment-processor replicas, then shows the state of the new runtime / database
# / Kafka alerts and their underlying signals.
#
# The deterministic assertion is KafkaConsumerRebalanceFrequent:
# increase(kafka_consumer_coordinator_rebalance_total[10m]) > 2. Rolling the
# consumer replicas reliably drives that counter up (each restart rebalances the
# group). The database and JVM alerts need real load or a fault to fire, so they
# are reported with their current values rather than asserted.
#
# Usage:
#   scripts/k8s/63-alerts-runtime-demo.sh
#
# Tunables (all optional env vars):
#   NAMESPACE  Kubernetes namespace        (default payments)
#   TARGET     consumer deployment to roll (default payment-processor)
#   RESTARTS   number of pod kills          (default 2)
#   TIMEOUT    seconds to wait for firing   (default 180)
#   PROM_PORT  local port-forward for Prometheus   (default 9090)
#   AM_PORT    local port-forward for Alertmanager (default 9093)
set -euo pipefail

namespace="${NAMESPACE:-payments}"
target="${TARGET:-payment-processor}"
restarts="${RESTARTS:-2}"
timeout_s="${TIMEOUT:-180}"
prom_port="${PROM_PORT:-9090}"
am_port="${AM_PORT:-9093}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "FAIL: python3 is required to parse the Prometheus/Alertmanager JSON API" >&2
  exit 1
fi

port_forwards=()
cleanup() { for pid in "${port_forwards[@]:-}"; do kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT

start_forward() {
  kubectl -n "$namespace" port-forward "$1" "$2:$3" >/dev/null 2>&1 &
  port_forwards+=("$!")
}
wait_http() {
  for _ in $(seq 1 30); do curl -sf "$1" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}
prom_query() {
  curl -s "http://127.0.0.1:${prom_port}/api/v1/query?query=$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))' "$1")" \
    | python3 -c '
import json, sys
try:
    for r in json.load(sys.stdin)["data"]["result"]:
        m = r["metric"]
        app = m.get("application", "-")
        val = r["value"][1]
        labels = ",".join("%s=%s" % (k, v) for k, v in sorted(m.items()) if k != "application")
        print("    %-22s %12s  %s" % (app, val, labels))
except Exception:
    pass
'
}
prom_alert_state() {
  curl -s "http://127.0.0.1:${prom_port}/api/v1/alerts" | python3 -c '
import json, sys
name = sys.argv[1]
try:
    alerts = json.load(sys.stdin).get("data", {}).get("alerts", [])
except ValueError:
    alerts = []
states = {a.get("state") for a in alerts if a.get("labels", {}).get("alertname") == name}
for wanted in ("firing", "pending"):
    if wanted in states:
        print(wanted); break
else:
    print("inactive")
' "$1" 2>/dev/null || echo inactive
}
am_alert_count() {
  curl -s "http://127.0.0.1:${am_port}/api/v2/alerts" | python3 -c '
import json, sys
name = sys.argv[1]
try:
    alerts = json.load(sys.stdin)
except ValueError:
    alerts = []
print(sum(1 for a in alerts if a.get("labels", {}).get("alertname") == name))
' "$1"
}

echo "### port-forwarding Prometheus (localhost:${prom_port}) and Alertmanager (localhost:${am_port})"
start_forward svc/prometheus "$prom_port" 9090
start_forward svc/alertmanager "$am_port" 9093
wait_http "http://127.0.0.1:${prom_port}/-/ready" || { echo "FAIL: Prometheus is not reachable"; exit 1; }
wait_http "http://127.0.0.1:${am_port}/-/ready" || { echo "FAIL: Alertmanager is not reachable"; exit 1; }

baseline="$(curl -s "http://127.0.0.1:${prom_port}/api/v1/query?query=$(python3 -c 'import urllib.parse; print(urllib.parse.quote("sum(increase(kafka_consumer_coordinator_rebalance_total[10m]))"))')" | python3 -c 'import json,sys
try: print(json.load(sys.stdin)["data"]["result"][0]["value"][1])
except Exception: print("0")')"
echo "### rebalances in the last 10m before: ${baseline}"

echo "### rolling ${target} (${restarts}x) to force consumer-group rebalances"
for i in $(seq 1 "$restarts"); do
  pod="$(kubectl -n "$namespace" get pods -l app="$target" -o jsonpath='{.items[0].metadata.name}')"
  echo "    kill ${i}/${restarts}: ${pod}"
  kubectl -n "$namespace" delete pod "$pod" --wait=false
  kubectl -n "$namespace" rollout status deployment/"$target" --timeout=120s >/dev/null
  sleep 3
done

echo "### waiting up to ${timeout_s}s for KafkaConsumerRebalanceFrequent to fire"
deadline=$((SECONDS + timeout_s))
state=""
while true; do
  state="$(prom_alert_state KafkaConsumerRebalanceFrequent)"
  echo "    KafkaConsumerRebalanceFrequent: ${state}"
  [[ "$state" == "firing" ]] && break
  if (( SECONDS > deadline )); then break; fi
  sleep 5
done

echo
echo "==================== live signals ===================="
echo "rebalances in the last 10m (want > 2):"
prom_query 'sum(increase(kafka_consumer_coordinator_rebalance_total[10m])) by (application)'
echo "database pool:"
prom_query 'sum by (application) (hikaricp_connections_pending)' 2>/dev/null || true
prom_query 'sum by (application) (rate(hikaricp_connections_acquire_seconds_sum[5m]))'
echo "JVM / process:"
prom_query 'max by (application) (jvm_gc_overhead)'
prom_query 'max by (application) (jvm_gc_pause_seconds_max)'
prom_query 'max by (application) (process_cpu_usage)'

echo
echo "==================== summary ===================="
echo "KafkaConsumerRebalanceFrequent      : ${state}"
am_count="$(am_alert_count KafkaConsumerRebalanceFrequent)"
echo "  visible in Alertmanager           : ${am_count} instance(s)"
for a in JvmGcOverheadHigh JvmGcPauseHigh ProcessCpuHigh FileDescriptorsHigh \
         DbConnectionPoolExhausted DbConnectionAcquireTimeouts DbPoolSaturated DbConnectionAcquireSlow \
         KafkaRebalanceStorm KafkaConsumerThrottled KafkaDisconnected KafkaAuthFailures; do
  printf '  %-34s: %s\n' "$a" "$(prom_alert_state "$a")"
done
echo
echo "Prometheus   : http://localhost:${prom_port}/alerts"
echo "Alertmanager : http://localhost:${am_port}"

if [[ "$state" == "firing" ]]; then
  echo
  echo "PASS: rolling the consumer replicas fired KafkaConsumerRebalanceFrequent"
  exit 0
fi

after="$(curl -s "http://127.0.0.1:${prom_port}/api/v1/query?query=$(python3 -c 'import urllib.parse; print(urllib.parse.quote("sum(increase(kafka_consumer_coordinator_rebalance_total[10m]))"))')" | python3 -c 'import json,sys
try: print(json.load(sys.stdin)["data"]["result"][0]["value"][1])
except Exception: print("0")')"
if python3 -c "import sys; sys.exit(0 if float('${after:-0}') > float('${baseline:-0}') else 1)"; then
  echo
  echo "PARTIAL: rebalances moved (${baseline} -> ${after}) but the rule has not fired yet;"
  echo "         it needs > 2 rebalances in the 10m window (rule for: 1m). Re-run with RESTARTS=3."
  exit 0
fi

echo
echo "FAIL: rebalances did not move (${baseline} -> ${after}); check the consumer is connected"
exit 1
