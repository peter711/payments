#!/usr/bin/env bash
# k8s variant of scripts/curl-full/00: sends a small batch to the payment-api
# running in k3d, through the Traefik ingress. Override the target with API_URL.
set -euo pipefail

api_url="${API_URL:-http://payments.localhost:8081}"

for i in 1 2 3 4 5; do
  curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' \
    -d '{"accountId":"ACC-1","amount":10,"currency":"PLN"}'; echo
  curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' \
    -d '{"accountId":"ACC-2","amount":20,"currency":"PLN"}'; echo
done
