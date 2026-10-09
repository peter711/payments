#!/usr/bin/env bash
# Consumes payments.completed from the k3d Kafka (successful outcomes published
# by payment-processor's outbox relay).
set -euo pipefail

namespace="${NAMESPACE:-payments}"

kubectl -n "$namespace" exec -i kafka-0 -- \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:19092 --topic payments.completed \
  --from-beginning --property print.key=true --property print.partition=true
