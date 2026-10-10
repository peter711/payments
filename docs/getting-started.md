# Getting started

## Prerequisites

| Tool | Needed for |
|---|---|
| JDK 17 | Building and running the services |
| Maven 3.9+ | Building the reactor (each service also ships `mvnw`) |
| Docker | The compose stacks (Kafka, services, monitoring) |
| k3d + kubectl | Optional — the two-replica Kubernetes stack ([deploy/k8s/README.md](../deploy/k8s/README.md)) |

## Build and test

The three services depend on the three shared libraries, so build the whole
reactor from the repository root:

```bash
mvn -q -DskipTests package     # compile every module
mvn -q test                    # run all tests
```

Run a single module with its wrapper:

```bash
cd payment-processor && ./mvnw test
```

The integration tests start an **embedded Kafka broker** (`@EmbeddedKafka`), so
`mvn test` needs no external Kafka. The processor and notification tests use H2.

## Ways to run the stack

### Option A — full containerised stack

Everything (Kafka, the three services, Prometheus, Grafana) in Docker:

```bash
cp .env.example .env                                   # Grafana admin login
docker compose -f docker-compose.full.yml up --build
```

| Surface | URL |
|---|---|
| payment-api | http://localhost:18080 |
| Kafka UI | http://localhost:18090 |
| Prometheus | http://localhost:19090 |
| Grafana | http://localhost:13000 |

### Option B — infra in Docker, services from the IDE

Best for debugging. Bring up only the infrastructure, then run the services from
your IDE (the `.run/` configurations already set the ports):

```bash
docker compose up -d          # Kafka, Kafka UI, Prometheus, Grafana
```

| Service | Run configuration | Port |
|---|---|---|
| payment-api | `PaymentApiApplication` | 9080 |
| payment-processor | `PaymentProcessorApplication` | 9081 |
| notification-service | `NotificationServiceApplication` | 9082 |

| Surface | URL |
|---|---|
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Kafka UI | http://localhost:8090 |

Prometheus reaches the host-run services through `host.docker.internal`.

### Option C — Kubernetes (k3d)

A two-replica deployment for every service on a local k3d cluster:

```bash
scripts/k8s/00-up.sh        # create the cluster + switch context
scripts/k8s/10-images.sh    # build and import the service images
scripts/k8s/20-deploy.sh    # apply the overlay, wait for readiness
scripts/k8s/30-verify.sh    # assert 2/2 replicas per service
scripts/k8s/40-smoke.sh     # send a couple of payments
scripts/k8s/90-teardown.sh  # delete the cluster
```

See [deploy/k8s/README.md](../deploy/k8s/README.md) for the full walkthrough.

## Send a payment

`paymentId` and `createdAt` are optional; a `paymentId` is generated when absent.
The optional `X-Correlation-Id` header ties events into one flow.

```bash
# against the full stack (Option A)
curl -s -X POST http://localhost:18080/payments \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"ACC-1","amount":10,"currency":"PLN"}'

# against services running from the IDE (Option B)
curl -s -X POST http://localhost:9080/payments \
  -H 'Content-Type: application/json' \
  -d '{"paymentId":"pay-123","accountId":"acc-456","amount":199.99,"currency":"PLN"}'
```

You can also use the Bruno collection in [`bruno/`](../bruno).

## Where to look

- **Kafka UI** — browse `payments.requested`, `payments.completed`,
  `payments.failed` and `payments.requested.DLT`.
- **Service logs** — the mock notification sender logs every notification; the
  processor logs processing, duplicates and dead letters.
- **Grafana** — the `Payments` dashboard folder shows throughput, outcomes,
  gateway latency, outbox backlog and the dead-letter rate
  ([observability/README.md](../observability/README.md)).
- **Demo scripts** — [operations.md](operations.md) covers the curl, Kafka,
  metrics-demo and failover helpers.
