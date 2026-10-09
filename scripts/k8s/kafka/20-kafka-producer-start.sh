#!/usr/bin/env bash
# Interactive producer for payments.requested on the k3d Kafka.
# Type key:value lines; Ctrl-C to stop.
set -euo pipefail

namespace="${NAMESPACE:-payments}"

kubectl -n "$namespace" exec -it kafka-0 -- \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:19092 --topic payments.requested \
  --property parse.key=true --property key.separator=:
