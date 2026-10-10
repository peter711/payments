# Observability

Metrics and logs for the payments system. Metrics are
[Micrometer](https://micrometer.io) meters exposed through **Spring Boot
Actuator** and scraped by **Prometheus**; logs are structured JSON on stdout,
collected by **Grafana Alloy** and stored in **Loki**. Both are visualized in a
single **Grafana**. Traces are still out of scope; the instrumentation uses the
Observation API so they can be added later without touching the call sites.

```
service  --(Micrometer meters)-->  /actuator/prometheus  <--scrape--  Prometheus  -->  Grafana
service  --(ECS JSON on stdout)--> Alloy  -->  Loki  ---------------------------------->  Grafana
```

## The `payments-observability` module

All three services depend on the shared `payments-observability` module. It is a
thin starter, **not** a telemetry SDK:

- brings `micrometer-registry-prometheus` (so `/actuator/prometheus` exists),
- attaches the common `application` and `env` tags to every meter,
- provides `LogContext`, a scoped helper that puts the payment flow's
  correlation context (`correlation_id`, `payment_id`, `event_id`, ...) on the
  SLF4J MDC,
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
| Loki | http://localhost:3100 |
| Kafka UI | http://localhost:8090 |

### Full containerized stack

```bash
docker compose -f docker-compose.full.yml up --build
```

| UI | URL |
|---|---|
| Grafana | http://localhost:13000 |
| Prometheus | http://localhost:19090 |
| Loki | http://localhost:13100 |
| payment-api | http://localhost:18080 |
| Kafka UI | http://localhost:18090 |

## Kubernetes (k3d)

The kustomize base pulls this directory in as a resource and turns the same files
into ConfigMaps (single source of truth for compose and k8s):

- `prometheus.k8s.yml` — pod discovery via `kubernetes_sd_configs`; only pods
  annotated with `prometheus.io/scrape="true"` are scraped.
- `prometheus/rules/payments-alerts.yml` — alerting rules.
- `loki/loki-config.yml` — Loki storage/query configuration.
- `alloy/config.k8s.alloy` — the log collection pipeline.
- `grafana/...` — datasources and dashboards, provisioned as code.

```bash
scripts/k8s/00-up.sh && scripts/k8s/10-images.sh && scripts/k8s/20-deploy.sh
```

Grafana is exposed at http://grafana.localhost:8081. Prometheus has no ingress;
use `kubectl -n payments port-forward svc/prometheus 9090:9090`.

### Generating traffic for the metrics dashboards

`scripts/k8s/70-metrics-demo.sh` fires a mixed workload (successes, declines,
idempotency duplicates, DLT-bound invalid/timeout payments and a final burst for
consumer lag) so every business meter has data:

```bash
scripts/k8s/70-metrics-demo.sh
API_URL=http://localhost:18080 scripts/k8s/70-metrics-demo.sh   # full compose stack
```

Tunables: `ROUNDS`, `BATCH`, `PARALLEL`, `BURST`, `SLEEP_BETWEEN`. The script
prints a summary and ready-to-use PromQL queries at the end.

## Logs

Logs are **structured JSON** (Elastic Common Schema) written to stdout, so
nothing is written to disk and the same line works in every environment.

- **Format**: Spring Boot's built-in ECS formatter
  (`logging.structured.format.console=ecs`, since Spring Boot 3.4). It is
  switched on where logs are collected — the compose services and the k8s
  deployments set `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`. Left unset (IDE runs)
  the services keep the human-readable console format.
- **Correlation**: `LogContext` (from `payments-observability`) puts
  `correlation_id`, `causation_id`, `event_id`, `event_type`, `payment_id`,
  `account_id` and `topic` on the MDC. The values come straight from the
  `EventEnvelope`, so one `correlation_id` pulls the whole flow together —
  `POST /payments` → `payments.requested` → gateway → outbox →
  `payments.completed`/`payments.failed` → notification.
- **Collection**: Grafana Alloy runs as a DaemonSet (k8s) or as a compose
  service; it parses the ECS JSON, keeps low-cardinality fields (`level`) as Loki
  labels and the correlation fields as **structured metadata** (searchable, never
  indexed as labels).
- **Storage**: Loki (single binary, filesystem storage, 7-day retention).
- **Query**: Grafana → Explore → Loki datasource, or the **Payments Logs**
  dashboard. Example:

  ```logql
  {app="payment-processor"} | json | correlation_id="<id>"
  ```

### Generating logs for the dashboard

`scripts/k8s/80-logs-demo.sh` drives the same mixed workload as the metrics
demo and prints ready-to-use LogQL queries:

```bash
scripts/k8s/80-logs-demo.sh
API_URL=http://localhost:18080 scripts/k8s/80-logs-demo.sh   # full compose stack
```

## Conventions

- **Naming**: metrics are named in dot notation in code (`payments.processor.outcomes`)
  and surface in Prometheus as snake_case (`payments_processor_outcomes_total`).
- **Cardinality**: never use `paymentId`, `accountId`, `correlationId` or any
  unbounded value as a tag. Those belong in logs/traces, not in the TSDB — and in
  Loki they are *structured metadata*, not index labels either.
- **Signals**: services follow RED (Rate / Errors / Duration); the pipeline adds
  the outbox backlog, publish failures, DLQ rate and Kafka consumer lag.
- **Configuration as code**: dashboards, datasources and alerts live in git and
  are provisioned, never clicked together in the UI.

### Adding a business metric

Inject `MeterRegistry` (see `PaymentProcessorMetrics`,
`NotificationMetrics`, `PaymentApiMetrics`) and register a `Counter`, `Timer`
or `Gauge` with a bounded tag set. `Gauge` is the right primitive for queue-like
depths, e.g. `payments.processor.outbox.pending`.

### Adding log context

Inject nothing: open a scope from `LogContext` for the duration of the work and
chain the fields you know. The scope restores the previous MDC values on close,
so it nests safely.

```java
try (var logContext = LogContext.scope()
        .correlationId(envelope.correlationId())
        .paymentId(event.paymentId())) {
    log.info("Processing payment");
}
```

The keys are flat and snake_case so they do not collide with the typed ECS field
names, and so Alloy can map them to Loki structured metadata.

## Verifying

```bash
curl -s localhost:8080/actuator/prometheus | grep ^payments   # metrics
docker compose -f docker-compose.full.yml logs payment-api | head   # ECS JSON logs
```

In Prometheus (`/targets`) all jobs should be `UP`; Grafana lists the dashboards
under the `Payments` folder. In Grafana → Explore → Loki, a query such as
`{app="payment-processor"}` should return the ECS JSON lines.
