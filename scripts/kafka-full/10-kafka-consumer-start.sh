#!/usr/bin/env bash
# Consumes payments.requested from the full-stack Kafka (what payment-api produces).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose_file="${COMPOSE_FILE:-$script_dir/../../docker-compose.full.yml}"

docker compose -f "$compose_file" exec -T kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:19092 --topic payments.requested \
  --from-beginning --property print.key=true --property print.partition=true
