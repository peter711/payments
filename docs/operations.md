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
scripts/k8s/80-logs-demo.sh     # drive traffic for the log dashboard
scripts/k8s/60-alerts-demo.sh   # fire DeadLetterMessages and assert delivery
scripts/k8s/61-alert-service-down.sh  # fire ServiceDown + check inhibition
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

## Generating data for the log dashboard

The same workload feeds Loki. `scripts/k8s/80-logs-demo.sh` sends a payment,
prints its `correlation_id` and then offers ready-to-use LogQL queries so the
whole flow can be followed from the API to the notification:

```bash
scripts/k8s/80-logs-demo.sh
API_URL=http://localhost:18080 scripts/k8s/80-logs-demo.sh   # full compose stack
```

Open Grafana → Explore → **Loki** (or the **Payments Logs** dashboard) and run:

```logql
{app="payment-processor"} | json | correlation_id="<id>"
```

Tunables: `ROUNDS`, `BATCH`, `PARALLEL`, `SLEEP_BETWEEN`.

## Alerts and demo scenarios

Prometheus evaluates the rules in `observability/prometheus/rules/payments-alerts.yml`
and forwards firing alerts to **Alertmanager**, which owns routing, grouping,
inhibition and delivery. The local sink is **Mailpit**, so every alert is visible
as a mail without any external service:

| Where | What |
|---|---|
| Prometheus `/alerts` | raw rule state (`pending` / `firing`) |
| Alertmanager (`:9093`, k3d `alertmanager.localhost:8081`) | grouped alerts, silences, inhibition |
| Mailpit (`:8025`, k3d `mailpit.localhost:8081`) | the delivered (and resolved) alert mails |
| Grafana → Alerting | the same alerts through the provisioned Alertmanager datasource |

Two scripts drive a real signal and assert the alert travelled end to end:

```bash
scripts/k8s/60-alerts-demo.sh          # negative-amount payment -> DLT -> DeadLetterMessages
scripts/k8s/61-alert-service-down.sh   # one failing scrape target -> ServiceDown
```

`60` sends payments the processor rejects permanently, so they are dead-lettered
and `DeadLetterMessages` fires after its 1m window. `61` patches one pod's
`prometheus.io/port` annotation to a closed port — the pod stays Running but the
scrape fails, so `up == 0` and `ServiceDown` fires; the annotation is restored by
a trap. Both print the Prometheus/Alertmanager/Mailpit URLs. Tunables: `API_URL`,
`NAMESPACE`, `ALERT`, `PAYMENTS`, `TIMEOUT`, `PROM_PORT`, `AM_PORT` (60) and
`TARGET`, `BAD_PORT`, `TIMEOUT`, `PROM_PORT` (61).

### Alert runbooks

Each rule links here through its `runbook_url`. Severity: `critical` pages,
`warning` is next-business-day, `info` is for the dashboards.

#### ServiceDown
`up == 0` for 1m. The pod is discovered but cannot be scraped. Check
`kubectl -n payments get pods`, the pod's readiness and
`kubectl -n payments logs <pod>`. Mutes the other alerts for the same `app`
(inhibition).

#### HighHttpErrorRate
More than 5% of HTTP requests return 5xx for 5m. Look at the failing endpoint
and the service logs; a downstream (Kafka, Postgres, gateway) is usually the
cause.

#### HighHttpLatency
p95 latency above 1s for 10m. Check the gateway latency panel and JVM/GC.

#### OutboxBacklogGrowing
More than 100 pending outbox rows for 10m. The relay is slower than the
producers or blocked. Check `payment-processor` logs and the Kafka publish rate.

#### OutboxStalled
Pending outbox rows but nothing published for 5m — the relay is stuck. Restart a
`payment-processor` replica and confirm the outbox drains.

#### OutboxPublishFailures
A publish attempt failed in the last 10m. Usually a Kafka broker/network blip;
the relay retries with backoff, so watch it clear. Persisting failures point at
Kafka availability.

#### DeadLetterMessages
At least one record dead-lettered in the last 10m. Inspect
`payments.requested.DLT` (`scripts/kafka*/40-kafka-dlt-consumer-start.sh`). A
single invalid payment is expected; a stream means a producer or schema change.

#### KafkaConsumerLagHigh
Consumer lag above 1000 for 10m. Scale `payment-processor` / `notification-service`
or check that the consumers are healthy.

#### PaymentProcessingFailureRateHigh
More than 50% of processed payments failed for 10m. Look at the outcome `reason`
in the logs and the mock-gateway behaviour.

#### GatewayLatencyHigh
p95 gateway call above 2s for 10m. The gateway is slow; check
`payment.mock.delay-ms` and the gateway latency panel.

#### PaymentEventPublishErrors
The API accepted payments but could not publish their events (last 10m). Check
Kafka connectivity from `payment-api`.

#### NotificationSendFailures
A notification failed to send in the last 10m. Check `notification-service` logs
and the sender.

#### DuplicateSpike
More than 50 duplicate payments skipped for 10m. Usually a consumer rebalance
loop or a redelivery storm; check consumer group membership and lag.

#### HighJvmHeapUsage
Heap above 85% for 10m. Check for a leak or lower `MaxRAMPercentage` / raise the
container memory limit.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Services idle, no messages consumed | Topics not created. `docker compose` runs a `kafka-init` job; for other setups run `scripts/kafka/00-kafka-topic-creation.sh`. |
| `payment-processor` keeps retrying a payment | It hit `ACC-TIMEOUT` or a gateway timeout; watch it move to `payments.requested.DLT` after the retries. |
| Grafana asks for a login | Use `.env` (`GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD`). On k3d the dev login is `admin`/`admin` (local overlay only). |
| `MissingVariableError: GRAFANA_ADMIN_USER` | `.env` missing — run `cp .env.example .env`. |
| Kafka UI empty on k3d | Wait for `kafka-init` to complete; `scripts/k8s/20-deploy.sh` already waits for it. |
| Grafana Loki datasource empty | The apps must emit ECS JSON (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) and send traffic. On the IDE stack logs stay on the console; use the full compose stack or k3d. Check Alloy: `kubectl -n payments logs ds/alloy`. |
| Port already in use | Change the host port in the relevant compose file or the k3d ports in `scripts/k8s/00-up.sh`. |
| No alert emails in Mailpit | Alertmanager must reach Mailpit (`mailpit:1025`) and Prometheus must reach Alertmanager (`alertmanager:9093`). Check `kubectl -n payments logs deploy/alertmanager` and Prometheus → Status → Alertmanagers (must be `UP`). |
| Alert stuck in `pending` | The rule's `for:` window has not elapsed yet — this is expected (e.g. `OutboxBacklogGrowing` waits 10m). |
