#!/usr/bin/env bash
# Consumes payments.requested from the k3d Kafka (what payment-api produces).
set -euo pipefail

namespace="${NAMESPACE:-payments}"

kubectl -n "$namespace" exec -i kafka-0 -- \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:19092 --topic payments.requested \
  --from-beginning --property print.key=true --property print.partition=true
