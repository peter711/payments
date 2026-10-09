#!/usr/bin/env bash
# Interactive producer for payments.requested on the full-stack Kafka.
# Type key:value lines; Ctrl-C to stop.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose_file="${COMPOSE_FILE:-$script_dir/../../docker-compose.full.yml}"

docker compose -f "$compose_file" exec kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:19092 --topic payments.requested \
  --property parse.key=true --property key.separator=:
