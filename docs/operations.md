# Operations and demo scripts

The `scripts/` directory holds small helpers, grouped by where they expect the
stack to be running. Scripts under `*-full` target the containerised
`docker-compose.full.yml` stack; the plain ones target services running from the
IDE (`localhost:9080`) and the infra-only compose file.

## HTTP scenarios — `scripts/curl*`

| Script | Scenario |
|---|---|
| `00-send-many-payments-curl.sh` | A small batch of well-formed payments |
| `10-send-flaky-and-invalid-curl.sh` | `ACC-FLAKY` (recovers after retries) and an invalid payload (→ DLT) |
| `20-send-duplicate-payment-curl.sh` | The same `paymentId` twice → one charge, one duplicate skip |
| `30-send-timeout-payment-curl.sh` | `ACC-TIMEOUT` → retried until exhausted → DLT |
| `40-send-without-payment-id-curl.sh` | No `paymentId`; the API generates one |
| `50-send-mixed-outcomes-curl.sh` | Successes, declines and dead letters together |
| `60-send-concurrent-duplicate-curl.sh` | The same `paymentId` fired concurrently |

| Folder | Target |
|---|---|
| `scripts/curl/` | `localhost:8080` (plain curl) |
| `scripts/curl-full/` | `http://localhost:18080` (`API_URL` overridable) |
| `scripts/k8s/curl/` | `http://payments.localhost:8081` (`API_URL` overridable) |

## Kafka helpers — `scripts/kafka*`

| Script | Purpose |
|---|---|
| `00-kafka-topic-creation.sh` / `00-kafka-topics-list.sh` | Create or list the four topics |
| `10-kafka-consumer-start.sh` | Consume `payments.requested` |
| `20-kafka-producer-start.sh` | Produce to `payments.requested` |
| `30-kafka-completed-consumer-start.sh` | Consume `payments.completed` |
| `35-kafka-failed-consumer-start.sh` | Consume `payments.failed` |
| `40-kafka-dlt-consumer-start.sh` | Consume `payments.requested.DLT` |
| `90-full-logs.sh` | Tail everything |

| Folder | Target |
|---|---|
| `scripts/kafka/` | `docker exec kafka` (infra-only compose) |
| `scripts/kafka-full/` | The full compose Kafka |
| `scripts/k8s/kafka/` | `kubectl exec` into the `kafka-0` pod |

## Kubernetes — `scripts/k8s/`

```bash
scripts/k8s/00-up.sh         # create the k3d cluster + switch context
scripts/k8s/10-images.sh     # build + import the service images
scripts/k8s/20-deploy.sh     # apply the local overlay, wait for readiness
scripts/k8s/30-verify.sh     # assert 2/2 ready replicas per service
scripts/k8s/40-smoke.sh      # send two payments through the ingress
scripts/k8s/50-failover.sh   # kill a replica, assert no double charge
scripts/k8s/70-metrics-demo.sh  # drive traffic for the dashboards
scripts/k8s/90-teardown.sh   # delete the cluster
```

Details and screenshots-worth of context live in
[deploy/k8s/README.md](../deploy/k8s/README.md).

### Failover and idempotency check

`scripts/k8s/50-failover.sh` delivers the same payments several times —
including while one `payment-processor` replica is being killed — then asserts
against the shared PostgreSQL:

- `gateway_charge` holds **exactly one** row per `paymentId` (no double charge);
- `outbox` holds **exactly one** outcome event per `paymentId`;
- `notification_log` holds **exactly one** notification per payment;
- the consumer group returns to **zero lag** and the deployment back to **2/2**.

Tunables: `PAYMENTS` (default 10), `NAMESPACE`, `API_URL`.

## Generating data for the dashboards

`scripts/k8s/70-metrics-demo.sh` fires a mixed workload (successes, declines,
idempotency duplicates, DLT-bound invalid and timeout payments, and a final burst
for consumer lag), then prints a summary and ready-to-paste PromQL queries.

```bash
scripts/k8s/70-metrics-demo.sh
API_URL=http://localhost:18080 scripts/k8s/70-metrics-demo.sh   # full compose stack
```

Tunables: `ROUNDS`, `BATCH`, `PARALLEL`, `BURST`, `SLEEP_BETWEEN`.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Services idle, no messages consumed | Topics not created. `docker compose` runs a `kafka-init` job; for other setups run `scripts/kafka/00-kafka-topic-creation.sh`. |
| `payment-processor` keeps retrying a payment | It hit `ACC-TIMEOUT` or a gateway timeout; watch it move to `payments.requested.DLT` after the retries. |
| Grafana asks for a login | Use `.env` (`GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD`). On k3d the dev login is `admin`/`admin` (local overlay only). |
| `MissingVariableError: GRAFANA_ADMIN_USER` | `.env` missing — run `cp .env.example .env`. |
| Kafka UI empty on k3d | Wait for `kafka-init` to complete; `scripts/k8s/20-deploy.sh` already waits for it. |
| Port already in use | Change the host port in the relevant compose file or the k3d ports in `scripts/k8s/00-up.sh`. |
