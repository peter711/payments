#!/usr/bin/env bash
# Tails the logs of the application services in the full stack. Handy while
# running the curl scenarios in scripts/curl-full.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose_file="${COMPOSE_FILE:-$script_dir/../../docker-compose.full.yml}"

docker compose -f "$compose_file" logs -f payment-api payment-processor notification-service
