#!/usr/bin/env bash
# Alerts demo: drives the pipeline into a state a business/pipeline alert fires,
# then asserts the alert travelled all the way to Alertmanager.
#
# By default it exercises DeadLetterMessages: a payment with a non-positive
# amount is a permanent validation failure, so payment-processor routes it
# straight to payments.requested.DLT (no retries), incrementing
# payments_processor_dlq_total. The rule fires after its 1m "for" window.
#
# Usage:
#   scripts/k8s/60-alerts-demo.sh
#
#   # full docker-compose stack instead of k3d (run a port-forward for Prometheus
#   # and Alertmanager first, or point PROM/=AM= at the published ports)
#   API_URL=http://localhost:18080 PROM_PORT=19090 AM_PORT=19093 \
#     scripts/k8s/60-alerts-demo.sh
#
# Tunables (all optional env vars):
#   API_URL    target base URL        (default http://payments.localhost:8081)
#   NAMESPACE  Kubernetes namespace   (default payments)
#   ALERT      alert name to assert   (default DeadLetterMessages)
#   PAYMENTS   dead-lettered payments (default 3)
#   TIMEOUT    seconds to wait for the alert to fire (default 180)
#   PROM_PORT  local port-forward for Prometheus   (default 9090)
#   AM_PORT    local port-forward for Alertmanager (default 9093)
set -euo pipefail

namespace="${NAMESPACE:-payments}"
api_url="${API_URL:-http://payments.localhost:8081}"
alert_name="${ALERT:-DeadLetterMessages}"
payments="${PAYMENTS:-3}"
timeout_s="${TIMEOUT:-180}"
prom_port="${PROM_PORT:-9090}"
am_port="${AM_PORT:-9093}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "FAIL: python3 is required to parse the Prometheus/Alertmanager JSON API" >&2
  exit 1
fi

port_forwards=()
cleanup() {
  for pid in "${port_forwards[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT

start_forward() {
  # $1 service, $2 local port, $3 remote port
  kubectl -n "$namespace" port-forward "$1" "$2:$3" >/dev/null 2>&1 &
  port_forwards+=("$!")
}

wait_http() {
  for _ in $(seq 1 30); do
    curl -sf "$1" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

prom_alert_state() {
  # Prints firing / pending / inactive for the named alert.
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
        print(wanted)
        break
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

rules_count="$(curl -s "http://127.0.0.1:${prom_port}/api/v1/rules" | python3 -c '
import json, sys
groups = json.load(sys.stdin).get("data", {}).get("groups", [])
print(sum(len(g.get("rules", [])) for g in groups))
')"
echo "### Prometheus loaded ${rules_count} alerting rules"

run_id="alert-demo-$(date +%s)"
echo "### sending ${payments} payments with a non-positive amount (-> payments.requested.DLT)"
for i in $(seq 1 "$payments"); do
  code="$(curl -s -o /dev/null -w '%{http_code}' \
    -X POST "$api_url/payments" -H 'Content-Type: application/json' \
    -d "{\"paymentId\":\"${run_id}-${i}\",\"accountId\":\"ACC-DEMO\",\"amount\":-1,\"currency\":\"PLN\"}" \
    2>/dev/null)" || code="000"
  echo "    ${run_id}-${i}: HTTP ${code}"
done

echo "### waiting up to ${timeout_s}s for '${alert_name}' to fire (rule has a 1m \"for\" window)"
deadline=$((SECONDS + timeout_s))
state=""
while true; do
  state="$(prom_alert_state "$alert_name")"
  echo "    ${alert_name}: ${state}"
  [[ "$state" == "firing" ]] && break
  if (( SECONDS > deadline )); then
    echo "FAIL: '${alert_name}' did not reach firing within ${timeout_s}s"
    exit 1
  fi
  sleep 5
done

echo "### waiting for Alertmanager to receive the notification (group_wait 10s)"
deadline=$((SECONDS + 60))
am_count=0
while true; do
  am_count="$(am_alert_count "$alert_name")"
  [[ "${am_count:-0}" -ge 1 ]] && break
  if (( SECONDS > deadline )); then
    break
  fi
  sleep 3
done

echo
echo "==================== summary ===================="
echo "run id          : ${run_id}"
echo "alert           : ${alert_name} -> ${state}"
echo "Alertmanager    : ${am_count} active instance(s)"
echo
echo "Prometheus   : http://localhost:${prom_port}/alerts"
echo "Alertmanager : http://localhost:${am_port}   (also http://alertmanager.localhost:8081)"
echo "Mailpit      : http://mailpit.localhost:8081   (alert email, needs a port-forward:"
echo "               kubectl -n ${namespace} port-forward svc/mailpit 8025:8025)"
echo
echo "The rule uses increase(...[10m]) > 0, so the alert stays firing for ~10m"
echo "and then resolves on its own. To silence it now: Alertmanager UI -> Silences -> New Silence."

if [[ "${am_count:-0}" -ge 1 ]]; then
  echo
  echo "PASS: '${alert_name}' fired in Prometheus and reached Alertmanager"
  exit 0
fi

echo
echo "FAIL: '${alert_name}' fired in Prometheus but Alertmanager has no instance"
echo "      check prometheus -> Status -> Alertmanagers, and alertmanager logs:"
echo "      kubectl -n ${namespace} logs deploy/alertmanager"
exit 1
