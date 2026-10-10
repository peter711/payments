#!/usr/bin/env bash
# Meta-alerts demo: proves the alerting pipeline monitors itself and that a
# notification actually leaves Prometheus -> Alertmanager -> receiver.
#
# It asserts three things:
#   1. Prometheus and Alertmanager each scrape themselves (the `up` targets for
#      the `prometheus` and `alertmanager` jobs are UP);
#   2. the new rule groups are loaded;
#   3. the Watchdog alert (vector(1), always firing) reached Alertmanager.
#
# Usage:
#   scripts/k8s/62-alerts-meta-demo.sh
#
# Tunables (all optional env vars):
#   NAMESPACE  Kubernetes namespace        (default payments)
#   TIMEOUT    seconds to wait for firing   (default 60)
#   PROM_PORT  local port-forward for Prometheus   (default 9090)
#   AM_PORT    local port-forward for Alertmanager (default 9093)
set -euo pipefail

namespace="${NAMESPACE:-payments}"
timeout_s="${TIMEOUT:-60}"
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
    print(json.load(sys.stdin)["data"]["result"])
except Exception:
    print([])
'
}
prom_scalar() {
  # First result value as a plain number, or 0.
  curl -s "http://127.0.0.1:${prom_port}/api/v1/query?query=$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))' "$1")" \
    | python3 -c '
import json, sys
try:
    print(json.load(sys.stdin)["data"]["result"][0]["value"][1])
except Exception:
    print("0")
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

echo "### 1/3 self-scrape targets"
self_up="$(prom_scalar 'count(up{job=~"prometheus|alertmanager"} == 1)')"
echo "    up{job=~\"prometheus|alertmanager\"} == 1 : ${self_up}"

echo "### 2/3 rule groups loaded"
groups="$(curl -s "http://127.0.0.1:${prom_port}/api/v1/rules" | python3 -c '
import json, sys
gs = json.load(sys.stdin).get("data", {}).get("groups", [])
print(",".join(sorted(g.get("name", "") for g in gs)))
')"
echo "    groups: ${groups}"
rules_count="$(curl -s "http://127.0.0.1:${prom_port}/api/v1/rules" | python3 -c '
import json, sys
print(sum(len(g.get("rules", [])) for g in json.load(sys.stdin).get("data", {}).get("groups", [])))
')"
echo "    total rules: ${rules_count}"

echo "### 3/3 Watchdog (always firing) reaches Alertmanager"
deadline=$((SECONDS + timeout_s))
wd_state=""
while true; do
  wd_state="$(prom_alert_state Watchdog)"
  echo "    Watchdog: ${wd_state}"
  [[ "$wd_state" == "firing" ]] && break
  if (( SECONDS > deadline )); then
    echo "FAIL: Watchdog did not fire within ${timeout_s}s"
    exit 1
  fi
  sleep 3
done
am_count=0
deadline=$((SECONDS + timeout_s))
while true; do
  am_count="$(am_alert_count Watchdog)"
  [[ "${am_count:-0}" -ge 1 ]] && break
  if (( SECONDS > deadline )); then break; fi
  sleep 3
done

echo
echo "==================== summary ===================="
echo "self-scrape targets (want 2) : ${self_up}"
echo "rule groups                  : ${groups}"
echo "rules                        : ${rules_count}"
echo "Watchdog -> Alertmanager     : ${am_count} active instance(s)"
echo
echo "Prometheus   : http://localhost:${prom_port}/alerts"
echo "Alertmanager : http://localhost:${am_port}"
echo "Mailpit      : http://mailpit.localhost:8081"

if [[ "${self_up:-0}" -ge 2 && "${am_count:-0}" -ge 1 ]]; then
  echo
  echo "PASS: Prometheus and Alertmanager are self-scraped and Watchdog is delivered"
  exit 0
fi

echo
echo "FAIL: self-scrape targets=${self_up:-0} (want 2), Watchdog in Alertmanager=${am_count:-0}"
echo "      check the 'prometheus'/'alertmanager' jobs under Prometheus -> Targets"
exit 1
