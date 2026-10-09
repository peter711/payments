#!/usr/bin/env bash
# Failover + idempotency check for the two-replica payment-processor.
#
# The same payments are delivered several times, including while one processor
# replica is being killed. Because the gateway ledger and the idempotency store
# live in the shared PostgreSQL, the surviving replica must take over without
# charging any payment twice:
#   - gateway_charge holds exactly one row per paymentId
#   - outbox holds exactly one outcome event per paymentId (unique key)
#   - the consumer group returns to zero lag and the deployment back to 2/2
#
# Requires the k3d stack to be up (scripts/k8s/20-deploy.sh).
set -euo pipefail

namespace="${NAMESPACE:-payments}"
api_url="${API_URL:-http://payments.localhost:8081}"
count="${PAYMENTS:-10}"
run="$(date +%s)"
prefix="pay-failover-${run}"

psql() {
  kubectl -n "$namespace" exec postgres-0 -- \
    psql -U payments -d payments -tA -c "$1"
}

send() {
  curl -s -o /dev/null -X POST "$api_url/payments" \
    -H 'Content-Type: application/json' \
    -d "{\"paymentId\":\"$1\",\"accountId\":\"ACC-FAILOVER\",\"amount\":25,\"currency\":\"PLN\"}"
}

echo "### run ${prefix}: delivering ${count} payments twice before failover"
for i in $(seq 1 "$count"); do
  send "${prefix}-${i}"
  send "${prefix}-${i}"
done

echo "### killing one payment-processor replica"
victim="$(kubectl -n "$namespace" get pods -l app=payment-processor -o jsonpath='{.items[0].metadata.name}')"
echo "    victim: ${victim}"
kubectl -n "$namespace" delete pod "$victim" --wait=false

echo "### re-delivering the same payments during the failover"
for i in $(seq 1 "$count"); do
  send "${prefix}-${i}"
  send "${prefix}-${i}"
done

echo "### waiting for every payment to reach a final state"
deadline=$((SECONDS + 120))
while true; do
  done_count="$(psql "SELECT count(*) FROM processed_payment WHERE payment_id LIKE '${prefix}-%' AND status <> 'IN_PROGRESS'")"
  done_count="${done_count:-0}"
  [[ "$done_count" == "$count" ]] && break
  if (( SECONDS > deadline )); then
    echo "FAIL: only ${done_count}/${count} payments reached a final state in time"
    exit 1
  fi
  sleep 3
done

echo "### waiting for notifications and outbox to settle"
deadline=$((SECONDS + 90))
while true; do
  notified="$(psql "SELECT count(*) FROM notification_log WHERE payment_id LIKE '${prefix}-%'")"
  notified="${notified:-0}"
  [[ "$notified" == "$count" ]] && break
  if (( SECONDS > deadline )); then
    break
  fi
  sleep 3
done

fail=0

charge_rows="$(psql "SELECT count(*) FROM gateway_charge WHERE payment_id LIKE '${prefix}-%'")"
if [[ "$charge_rows" == "$count" ]]; then
  echo "OK   gateway_charge: ${charge_rows}/${count} (no double charge)"
else
  echo "FAIL gateway_charge: ${charge_rows}/${count} (expected exactly one charge per payment)"
  fail=1
fi

outbox_rows="$(psql "SELECT count(*) FROM outbox WHERE payment_id LIKE '${prefix}-%'")"
if [[ "$outbox_rows" == "$count" ]]; then
  echo "OK   outbox: ${outbox_rows}/${count} (one outcome event per payment)"
else
  echo "FAIL outbox: ${outbox_rows}/${count}"
  fail=1
fi

if [[ "${notified:-0}" == "$count" ]]; then
  echo "OK   notification_log: ${notified}/${count} (no duplicate notification)"
else
  echo "FAIL notification_log: ${notified:-0}/${count}"
  fail=1
fi

echo "### waiting for the consumer group and the replicas to recover"
deadline=$((SECONDS + 120))
while true; do
  lag="$(kubectl -n "$namespace" exec kafka-0 -- \
    /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:19092 \
    --describe --group payment-processor 2>/dev/null \
    | awk 'NR>1 {s+=$6} END {print s+0}')"
  ready="$(kubectl -n "$namespace" get deploy payment-processor -o jsonpath='{.status.readyReplicas}')"
  if [[ "${lag:-1}" == "0" && "${ready:-0}" == "2" ]]; then
    break
  fi
  if (( SECONDS > deadline )); then
    echo "WARN: lag=${lag:-?}, ready=${ready:-?} after failover window"
    fail=1
    break
  fi
  sleep 3
done
kubectl -n "$namespace" get deploy payment-processor \
  -o custom-columns=NAME:.metadata.name,READY:.status.readyReplicas

if [[ "$fail" == "0" ]]; then
  echo "PASS: ${prefix} survived the replica kill with exactly one charge per payment"
else
  echo "FAIL: ${prefix} assertions failed"
fi

exit "$fail"
