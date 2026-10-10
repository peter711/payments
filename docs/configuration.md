# Configuration

## Spring profiles

| Profile | Database | Used by |
|---|---|---|
| *(default)* | H2 file, `./data/<service>` | Local development, IDE runs, compose |
| `k8s` | Shared PostgreSQL | Clustered deployment (two replicas) |

The `k8s` profile is activated with `SPRING_PROFILES_ACTIVE=k8s` (the Kubernetes
deployments set it). It switches the datasource to PostgreSQL and loads
`schema-postgres.sql`; every other setting is inherited from the default profile.

All services import the shared defaults from `payments-observability.yml` via
`spring.config.import: classpath:payments-observability.yml`.

## Key properties

### payment-api — `application.yml`

| Property | Default | Notes |
|---|---|---|
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Overridden by `SPRING_KAFKA_BOOTSTRAP_SERVERS` |
| `spring.kafka.producer.acks` | `all` | Strong ack before the event is considered sent |
| `spring.kafka.producer.properties.enable.idempotence` | `true` | Broker-side producer idempotence |

### payment-processor — `application.yml`

| Property | Default | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:h2:file:./data/payment-processor;AUTO_SERVER=TRUE` | Durable inbox + outbox |
| `payment.mock.success-rate` | `0.8` | Probability the mock gateway accepts |
| `payment.mock.delay-ms` | `200` | Simulated gateway latency |
| `payment.outbox.poll-interval-ms` | `500` | Relay poll period |
| `payment.outbox.batch-size` | `100` | Rows claimed per poll |
| `payment.outbox.max-attempts` | `10` | Publish attempts before a row is `FAILED` |
| `payment.outbox.retry-backoff-ms` | `1000` | Initial retry backoff |
| `payment.outbox.max-backoff-ms` | `60000` | Backoff cap |
| `payment.outbox.cleanup-interval-ms` | `3600000` | Purge cadence for published rows |
| `payment.outbox.retention` | `24h` | How long published rows are kept |

### notification-service — `application.yml`

| Property | Default | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:h2:file:./data/notification-service;AUTO_SERVER=TRUE` | Deduplication log |
| `spring.kafka.consumer.group-id` | `notification-service` | Consumer group |

### Shared observability — `payments-observability.yml`

| Property | Default | Notes |
|---|---|---|
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Explicit list, never `*` |
| `management.observations.key-values.env` | `${PAYMENTS_ENV:local}` | `env` tag on every meter |
| `management.metrics.distribution.percentiles-histogram.*` | — | Histograms for p95/p99 via `histogram_quantile` |
| `logging.structured.format.console` | *(unset — plain text)* | Set to `ecs` in compose/k8s (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) for ECS JSON logs |
| `logging.structured.ecs.service.environment` | `${PAYMENTS_ENV:local}` | `service.environment` in the ECS JSON logs |

## Environment variables

| Variable | Default | Where | Purpose |
|---|---|---|---|
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | compose / k8s | Kafka bootstrap servers |
| `SPRING_PROFILES_ACTIVE` | — | k8s | Activates the PostgreSQL profile (`k8s`) |
| `DB_USERNAME` | `payments` | k8s | PostgreSQL user |
| `DB_PASSWORD` | *(none — required)* | k8s | PostgreSQL password; a missing value fails fast |
| `PAYMENTS_ENV` | `local` | k8s | The `env` metric tag (k8s sets `production`) |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | *(unset)* | compose / k8s | `ecs` emits ECS JSON logs (collected by Alloy → Loki); unset keeps plain text |
| `GRAFANA_ADMIN_USER` | *(from `.env`)* | compose | Grafana admin login |
| `GRAFANA_ADMIN_PASSWORD` | *(from `.env`)* | compose | Grafana admin password |
| `JAVA_TOOL_OPTIONS` | — | k8s | JVM flags (e.g. `-XX:MaxRAMPercentage=75`) |

## Ports

| Component | Default / container | IDE (`.run/`) | Full compose | k3d ingress |
|---|---|---|---|---|
| payment-api | 8080 | 9080 | 18080 | `payments.localhost:8081` |
| payment-processor | 8080 (actuator) | 9081 | — | internal |
| notification-service | 8080 (actuator) | 9082 | — | internal |
| Kafka (host listener) | 9092 | 9092 | 29092 | internal |
| Kafka UI | 8090 | 8090 | 18090 | `kafka-ui.localhost:8081` |
| Prometheus | 9090 | 9090 | 19090 | port-forward |
| Loki | — | 3100 | 13100 | internal |
| Alloy (UI) | — | 12345 | 12345 | internal |
| Grafana | 3000 | 3000 | 13000 | `grafana.localhost:8081` |

## Secrets

Credentials are never committed:

- **Compose** reads the Grafana admin login from `.env`. Copy the template and
  adjust it:

  ```bash
  cp .env.example .env
  ```

  `.env` is gitignored; a missing file makes `docker compose` fail with a clear
  message instead of falling back to a known password.

- **Kubernetes** references secrets that are provisioned out-of-band:
  - `postgres-credentials` (`username`/`password`) — template in
    [`deploy/k8s/base/postgres/secret.example.yaml`](../deploy/k8s/base/postgres/secret.example.yaml);
  - the Grafana admin login is added only by the local overlay.
