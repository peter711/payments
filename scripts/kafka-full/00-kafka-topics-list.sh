#!/usr/bin/env bash
# Lists the topics in the full-stack Kafka. kafka-init creates them on startup.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose_file="${COMPOSE_FILE:-$script_dir/../../docker-compose.full.yml}"

docker compose -f "$compose_file" exec -T kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --list
