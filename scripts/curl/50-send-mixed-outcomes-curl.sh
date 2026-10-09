#!/usr/bin/env bash
# Mixed-outcome batch. With the default success rate (0.8) roughly one in five
# payments is declined by the mock gateway and lands on payments.failed; the rest
# land on payments.completed.
set -euo pipefail

for i in $(seq 1 10); do
  curl -s -X POST localhost:8080/payments -H 'Content-Type: application/json' \
    -d '{"paymentId":"pay-rand-'"$(date +%s)"'-'"$i"'","accountId":"ACC-RANDOM","amount":5,"currency":"PLN"}'; echo
done

echo "Consume payments.completed / payments.failed to see the split."
