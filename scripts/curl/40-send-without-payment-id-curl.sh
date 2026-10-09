#!/usr/bin/env bash
# Missing paymentId on purpose: the API generates a UUID (the idempotency key)
# and echoes it back in the 202 response, so clients can safely retry.
set -euo pipefail

echo "Sending a payment without paymentId; watch the generated id in the response:"
curl -s -X POST localhost:8080/payments -H 'Content-Type: application/json' \
  -d '{"accountId":"ACC-GENERATED","amount":15,"currency":"PLN"}'; echo
