#!/usr/bin/env bash
# Lists the topics in the k3d Kafka. kafka-init creates them on startup.
set -euo pipefail

namespace="${NAMESPACE:-payments}"

kubectl -n "$namespace" exec kafka-0 -- \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --list
