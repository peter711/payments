for i in 1 2 3 4 5; do
  curl -s -X POST localhost:8080/payments -H 'Content-Type: application/json' \
    -d '{"accountId":"ACC-1","amount":10,"currency":"PLN"}'; echo
  curl -s -X POST localhost:8080/payments -H 'Content-Type: application/json' \
    -d '{"accountId":"ACC-2","amount":20,"currency":"PLN"}'; echo
done