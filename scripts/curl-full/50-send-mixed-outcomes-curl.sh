#!/usr/bin/env bash
# Full-stack mixed-outcome batch. With the default success rate (0.8) roughly one
# in five payments is declined by the mock gateway and lands on payments.failed;
# the rest land on payments.completed.
set -euo pipefail

api_url="${API_URL:-http://localhost:18080}"
for i in $(seq 1 10); do
  curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' \
    -d '{"paymentId":"pay-rand-'"$(date +%s)"'-'"$i"'","accountId":"ACC-RANDOM","amount":5,"currency":"PLN"}'; echo
done

echo "Consume the outcomes to see the split:"
echo "  scripts/kafka-full/30-kafka-completed-consumer-start.sh"
echo "  scripts/kafka-full/35-kafka-failed-consumer-start.sh"
