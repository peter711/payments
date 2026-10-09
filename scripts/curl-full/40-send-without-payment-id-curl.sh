#!/usr/bin/env bash
# Full-stack variant of scripts/curl/40: missing paymentId on purpose. The API
# generates a UUID (the idempotency key) and echoes it back in the 202 response.
set -euo pipefail

api_url="${API_URL:-http://localhost:18080}"
echo "Sending a payment without paymentId; watch the generated id in the response:"
curl -s -X POST "$api_url/payments" -H 'Content-Type: application/json' \
  -d '{"accountId":"ACC-GENERATED","amount":15,"currency":"PLN"}'; echo
