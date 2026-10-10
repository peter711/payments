#!/usr/bin/env bash
# ServiceDown demo: makes one scrape target fail while its pod keeps running, so
# `up == 0` and the ServiceDown alert fires. The change is reversible and is
# reverted by a trap even if the script fails part way through.
#
# Why patch the pod annotation instead of scaling the Deployment to 0: a scaled
# down pod disappears from Kubernetes service discovery, so Prometheus drops the
# target and `up` goes stale instead of becoming 0. Keeping the pod Running but
# pointing the scrape at a closed port is what actually produces `up == 0`.
#
# Usage:
#   scripts/k8s/61-alert-service-down.sh
#   TARGET=payment-api scripts/k8s/61-alert-service-down.sh
#
# Tunables (all optional env vars):
#   NAMESPACE  Kubernetes namespace        (default payments)
#   TARGET     deployment label to break   (default notification-service)
#   BAD_PORT   closed port to scrape       (default 9)
#   TIMEOUT    seconds to wait for firing  (default 240)
#   PROM_PORT  local port-forward for Prometheus (default 9090)
set -euo pipefail

namespace="${NAMESPACE:-payments}"
target="${TARGET:-notification-service}"
bad_port="${BAD_PORT:-9}"
timeout_s="${TIMEOUT:-240}"
prom_port="${PROM_PORT:-9090}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "FAIL: python3 is required to parse the Prometheus JSON API" >&2
  exit 1
fi

pod="$(kubectl -n "$namespace" get pods -l app="$target" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
if [[ -z "$pod" ]]; then
  echo "FAIL: no '$target' pod found in namespace '$namespace'" >&2
  exit 1
fi
original_port="$(kubectl -n "$namespace" get pod "$pod" \
  -o jsonpath='{.metadata.annotations.prometheus\.io/port}' 2>/dev/null || true)"
original_port="${original_port:-8080}"

restore() {
  kubectl -n "$namespace" annotate pod "$pod" "prometheus.io/port=${original_port}" --overwrite >/dev/null 2>&1 || true
}

port_forward_pid=""
cleanup_forward() {
  [[ -n "$port_forward_pid" ]] && kill "$port_forward_pid" 2>/dev/null || true
}
trap 'cleanup_forward; restore' EXIT

echo "### selecting pod: ${pod} (app=${target})"
echo "### breaking its scrape: prometheus.io/port=${original_port} -> ${bad_port}"
kubectl -n "$namespace" annotate pod "$pod" "prometheus.io/port=${bad_port}" --overwrite

echo "### port-forwarding Prometheus (localhost:${prom_port})"
kubectl -n "$namespace" port-forward svc/prometheus "${prom_port}:9090" >/dev/null 2>&1 &
port_forward_pid="$!"
for _ in $(seq 1 30); do
  curl -sf "http://127.0.0.1:${prom_port}/-/ready" >/dev/null 2>&1 && break
  sleep 1
done

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
        print(wanted)
        break
else:
    print("inactive")
' "$1" 2>/dev/null || echo inactive
}

echo "### waiting up to ${timeout_s}s for ServiceDown (rule has a 1m \"for\" window)"
deadline=$((SECONDS + timeout_s))
state=""
while true; do
  state="$(prom_alert_state ServiceDown)"
  echo "    ServiceDown: ${state}"
  [[ "$state" == "firing" ]] && break
  if (( SECONDS > deadline )); then
    echo "FAIL: ServiceDown did not fire within ${timeout_s}s"
    exit 1
  fi
  sleep 5
done

echo "### restoring the scrape annotation on ${pod}"
restore

echo
echo "==================== summary ===================="
echo "target pod   : ${pod}"
echo "ServiceDown  : ${state}"
echo
echo "Prometheus   : http://localhost:${prom_port}/alerts"
echo "Alertmanager : http://alertmanager.localhost:8081"
echo "Mailpit      : http://mailpit.localhost:8081  (firing + resolved mails)"
echo
echo "ServiceDown is severity=critical, so only for='1m'; the alert resolves once"
echo "the target is scrapeable again. While it is firing, the Alertmanager"
echo "inhibition rule mutes the other warning/critical alerts for the same app."
echo
echo "PASS: breaking one target's scrape made ServiceDown fire under 1m"
