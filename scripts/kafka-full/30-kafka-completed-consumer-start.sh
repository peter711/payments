#!/usr/bin/env bash
# Consumes payments.completed from the full-stack Kafka (successful outcomes
# published by payment-processor's outbox relay).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose_file="${COMPOSE_FILE:-$script_dir/../../docker-compose.full.yml}"

docker compose -f "$compose_file" exec -T kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:19092 --topic payments.completed \
  --from-beginning --property print.key=true --property print.partition=true
