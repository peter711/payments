# Observability

Metrics for the payments system: [Micrometer](https://micrometer.io) (the
Spring-native metrics API) exposed through **Spring Boot Actuator** and scraped
by **Prometheus**, visualized in **Grafana**. Traces and logs are deliberately
out of scope for now; the instrumentation uses the Observation API so they can
be added later without touching the call sites.

```
service  --(Micrometer meters)-->  /actuator/prometheus  <--scrape--  Prometheus  -->  Grafana
```

## The `payments-observability` module

All three services depend on the shared `payments-observability` module. It is a
thin starter, **not** a telemetry SDK:

- brings `micrometer-registry-prometheus` (so `/actuator/prometheus` exists),
- attaches the common `application` and `env` tags to every meter,
- ships `payments-observability.yml`, imported by each service with
  `spring.config.import: classpath:payments-observability.yml`.

Common tag values:

| Tag | Source |
|---|---|
| `application` | `spring.application.name` |
| `env` | `PAYMENTS_ENV` (default `local`; the k8s deployments set it to `production`) |

## Running locally

Grafana's admin login for both compose files is read from `.env`. Copy the
template once:

```bash
cp .env.example .env
```

### Services from the IDE (Kafka + monitoring in Docker)

```bash
docker compose up -d          # Kafka, Kafka UI, Prometheus, Grafana
```

Start the services from the IDE (`.run/` sets `-Dserver.port=9080/9081/9082`).
Prometheus scrapes them through `host.docker.internal`.

| UI | URL |
|---|---|
| Grafana | http://localhost:3000 (anonymous admin; admin login from `.env`) |
| Prometheus | http://localhost:9090 |
| Kafka UI | http://localhost:8090 |

### Full containerized stack

```bash
docker compose -f docker-compose.full.yml up --build
```

| UI | URL |
|---|---|
| Grafana | http://localhost:13000 |
| Prometheus | http://localhost:19090 |
| payment-api | http://localhost:18080 |
| Kafka UI | http://localhost:18090 |

## Kubernetes (k3d)

The kustomize base pulls this directory in as a resource and turns the same files
into ConfigMaps (single source of truth for compose and k8s):

- `prometheus.k8s.yml` — pod discovery via `kubernetes_sd_configs`; only pods
  annotated with `prometheus.io/scrape="true"` are scraped.
- `prometheus/rules/payments-alerts.yml` — alerting rules.
- `grafana/...` — datasource and dashboards, provisioned as code.

```bash
scripts/k8s/00-up.sh && scripts/k8s/10-images.sh && scripts/k8s/20-deploy.sh
```

Grafana is exposed at http://grafana.localhost:8081. Prometheus has no ingress;
use `kubectl -n payments port-forward svc/prometheus 9090:9090`.

### Generating traffic for the dashboards

`scripts/k8s/70-metrics-demo.sh` fires a mixed workload (successes, declines,
idempotency duplicates, DLT-bound invalid/timeout payments and a final burst for
consumer lag) so every business meter has data:

```bash
scripts/k8s/70-metrics-demo.sh
API_URL=http://localhost:18080 scripts/k8s/70-metrics-demo.sh   # full compose stack
```

Tunables: `ROUNDS`, `BATCH`, `PARALLEL`, `BURST`, `SLEEP_BETWEEN`. The script
prints a summary and ready-to-use PromQL queries at the end.

## Conventions

- **Naming**: metrics are named in dot notation in code (`payments.processor.outcomes`)
  and surface in Prometheus as snake_case (`payments_processor_outcomes_total`).
- **Cardinality**: never use `paymentId`, `accountId`, `correlationId` or any
  unbounded value as a tag. Those belong in logs/traces, not in the TSDB.
- **Signals**: services follow RED (Rate / Errors / Duration); the pipeline adds
  the outbox backlog, publish failures, DLQ rate and Kafka consumer lag.
- **Configuration as code**: dashboards, datasources and alerts live in git and
  are provisioned, never clicked together in the UI.

### Adding a business metric

Inject `MeterRegistry` (see `PaymentProcessorMetrics`,
`NotificationMetrics`, `PaymentApiMetrics`) and register a `Counter`, `Timer`
or `Gauge` with a bounded tag set. `Gauge` is the right primitive for queue-like
depths, e.g. `payments.processor.outbox.pending`.

## Verifying

```bash
curl -s localhost:8080/actuator/prometheus | grep ^payments
```

In Prometheus (`/targets`) all jobs should be `UP`; Grafana lists the dashboards
under the `Payments` folder.
