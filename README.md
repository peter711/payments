# Payments

A small **event-driven payments platform** built with Spring Boot and Kafka:
submit a payment over HTTP, have it processed against a (mock) payment gateway,
and receive a notification on the outcome.

The interesting part is not the happy path but what happens when things go
wrong: Kafka only guarantees *at-least-once* delivery, yet a payment must be
**charged and notified at most once**. The platform solves this with a durable
consumer inbox, a gateway idempotency ledger and a transactional outbox.

## What it demonstrates

- **Contract-first events** — versioned JSON Schemas shareable across services.
- **Idempotent consumers** — a durable inbox plus a gateway ledger, so retries
  and restarts never double-charge or double-notify.
- **Transactional outbox** — the outcome and its outgoing event are committed in
  one transaction and published by a scheduled relay with retries and a
  dead-letter topic.
- **Cluster-safe publishing** — the PostgreSQL relay claims rows with
  `FOR UPDATE SKIP LOCKED`, so two replicas never publish the same event.
- **Two local stacks** — `docker compose` and a two-replica k3d / Kubernetes
  deployment (kustomize).
- **Observability as code** — Micrometer → Actuator → Prometheus for metrics and
  ECS JSON logs → Alloy → Loki, both visualized in one Grafana, with dashboards
  and alerts provisioned as code.

## Architecture

```
                 POST /payments
                       │
                       ▼
                ┌──────────────┐   payments.requested   ┌───────────────────┐
                │  payment-api │ ─────────────────────▶ │ payment-processor │
                └──────────────┘                        └─────────┬─────────┘
                                                                  │ (mock) gateway
                                                       outcome + outbox event
                                                       committed in one transaction
                                                                  │
                                          payments.completed / payments.failed
                                                                  ▼
                                                    ┌───────────────────────┐
                                                    │  notification-service │
                                                    └───────────────────────┘

  invalid payload / retries exhausted ──▶ payments.requested.DLT
```

Every Kafka record is wrapped in a self-describing [`EventEnvelope`](docs/architecture.md#event-envelope)
carrying the event id, type, version and correlation/causation ids.

## Modules

| Module | Type | Responsibility |
|---|---|---|
| [`payments-contracts`](payments-contracts) | library | Kafka topics, event envelope, event types and JSON Schemas |
| [`payments-observability`](payments-observability) | library (starter) | Micrometer/Prometheus defaults, `LogContext` correlation, shared YAML |
| [`payment-api`](payment-api) | Spring Boot app | REST `POST /payments` → `payments.requested` |
| [`payment-processor`](payment-processor) | Spring Boot app | Consume requests, call the gateway, idempotency + outbox → `completed`/`failed` |
| [`notification-service`](notification-service) | Spring Boot app | Consume outcomes, deduplicate, send notifications |

Only the last three are runnable services; the first two are shared libraries.

## Quick start

The fastest way to see the whole pipeline is the full compose stack:

```bash
cp .env.example .env                                   # Grafana admin login
docker compose -f docker-compose.full.yml up --build   # Kafka + services + monitoring

curl -s -X POST http://localhost:18080/payments \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"ACC-1","amount":10,"currency":"PLN"}'
```

Then look at Grafana (<http://localhost:13000>) or the topic contents in Kafka UI
(<http://localhost:18090>). Other options — running services from your IDE, or
the two-replica Kubernetes stack — are covered in
[docs/getting-started.md](docs/getting-started.md).

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Components, end-to-end flow, contracts, idempotency and the outbox |
| [docs/getting-started.md](docs/getting-started.md) | Prerequisites, build/test, the three ways to run the stack |
| [docs/configuration.md](docs/configuration.md) | Profiles, properties, environment variables, ports, secrets |
| [docs/operations.md](docs/operations.md) | Demo scripts, scenarios, failover test, troubleshooting |
| [docs/development.md](docs/development.md) | Module layout, conventions, extending events and metrics |
| [deploy/k8s/README.md](deploy/k8s/README.md) | The k3d / kustomize deployment in detail |
| [observability/README.md](observability/README.md) | Metrics, dashboards and alerts in detail |

## Repository layout

```
.
├── payment-api/              # REST entry point (Spring Boot)
├── payment-processor/        # Kafka consumer + gateway + outbox
├── notification-service/     # Kafka consumer + notification sender
├── payments-contracts/       # shared events, topics and JSON Schemas
├── payments-observability/   # shared metrics conventions
├── deploy/k8s/               # kustomize base + local (k3d) overlay
├── observability/            # Prometheus/Loki config, Alloy pipeline, Grafana dashboards
├── scripts/                  # curl / kafka / k8s demo helpers
├── bruno/                    # Bruno HTTP collection
├── docker-compose.yml        # infra only (Kafka + monitoring) for IDE dev
├── docker-compose.full.yml   # full containerised stack
└── Dockerfile                # one multi-stage build for the three services
```

## Requirements

- **JDK 17** and **Maven 3.9+** (or a module's `mvnw` wrapper)
- **Docker** for the compose stacks
- Optional: **k3d**, **kubectl** and **k9s** for the Kubernetes stack
