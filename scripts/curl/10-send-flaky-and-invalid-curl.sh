#!/usr/bin/env bash
# Sends one flaky payment (retried until the unstable gateway recovers) and one
# invalid payment (routed straight to payments.requested.DLT).
set -euo pipefail

curl -s -X POST localhost:9080/payments -H 'Content-Type: application/json' \
  -d '{"paymentId":"pay-flaky","accountId":"ACC-FLAKY","amount":42,"currency":"PLN"}'; echo

curl -s -X POST localhost:9080/payments -H 'Content-Type: application/json' \
  -d '{"paymentId":"pay-invalid","accountId":"ACC-1","amount":-100,"currency":"PLN"}'; echo
