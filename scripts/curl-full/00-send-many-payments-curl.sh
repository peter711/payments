#!/usr/bin/env bash
# Full-stack variant of scripts/curl/00: sends a small batch to the
# containerised payment-api (docker-compose.full.yml, port 18080).
# Override the target with API_URL if needed.
set -euo pipefail

api_url="${API_URL:-http://localhost:18080}"

for i in 1 2 3 4 5; do
  curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' \
    -d '{"accountId":"ACC-1","amount":10,"currency":"PLN"}'; echo
  curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' \
    -d '{"accountId":"ACC-2","amount":20,"currency":"PLN"}'; echo
done
