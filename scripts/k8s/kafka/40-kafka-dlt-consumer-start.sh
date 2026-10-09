#!/usr/bin/env bash
# Inspects records that ended up on the k3d dead letter topic.
set -euo pipefail

namespace="${NAMESPACE:-payments}"

kubectl -n "$namespace" exec -i kafka-0 -- \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:19092 --topic payments.requested.DLT \
  --from-beginning --property print.key=true --property print.partition=true \
  --property print.headers=true
