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
| `payment.gateway.provider` | `mock` | `mock` hides the webhook endpoint; `stripe` exposes `POST /webhooks/stripe` |
| `payment.stripe.api-key` | *(none — required for `stripe`)* | Stripe secret key (`sk_test_...` in the sandbox) |
| `payment.stripe.webhook-secret` | *(none — required for `stripe`)* | Signing secret (`whsec_...`) used to verify `Stripe-Signature`; startup fails when the provider is `stripe` without it |

With `payment.gateway.provider=stripe`, `POST /webhooks/stripe` is registered and
publishes a `payment.provider_event` for each supported payment-intent webhook.
An invalid signature returns `400`; an unsupported event or one with no
`paymentId` metadata returns `200` without publishing. See
[operations.md](operations.md#local-stripe-webhooks) for the local `stripe
listen` workflow.

### payment-processor — `application.yml`

| Property | Default | Notes |
|---|---|---|
| `spring.datasource.url` | `jdbc:h2:file:./data/payment-processor;AUTO_SERVER=TRUE` | Durable inbox + outbox |
| `payment.gateway.provider` | `mock` | `mock` or `stripe`; selects the `PaymentGateway` adapter |
| `payment.mock.success-rate` | `0.8` | Probability the mock gateway accepts |
| `payment.mock.delay-ms` | `200` | Simulated gateway latency |
| `payment.stripe.api-key` | *(none — required for `stripe`)* | Stripe secret key (`sk_test_...` in the sandbox) |
| `payment.stripe.api-base` | `https://api.stripe.com` | Stripe API base URL (overridden by tests) |
| `payment.stripe.payment-method` | `pm_card_visa` | Payment method confirmed on the PaymentIntent |
| `payment.stripe.timeout-ms` | `5000` | Connect/read timeout for a Stripe call |
| `payment.stripe.max-network-retries` | `2` | Automatic SDK retries of a failed request |
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
| `PAYMENT_GATEWAY_PROVIDER` | `mock` | compose / k8s | Selects the gateway adapter (`mock` or `stripe`) |
| `STRIPE_API_KEY` | *(none)* | compose / k8s | Stripe secret key; required when the provider is `stripe` |
| `STRIPE_API_BASE` | `https://api.stripe.com` | — | Optional Stripe API base override |
| `STRIPE_PAYMENT_METHOD` | `pm_card_visa` | — | Optional sandbox payment method |
| `STRIPE_WEBHOOK_SECRET` | *(none)* | compose / k8s | Stripe webhook signing secret (`whsec_...`); required when the provider is `stripe` |
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
  message instead of falling back to a known password. The same file carries the
  Stripe secret key (`STRIPE_API_KEY`, empty by default) used when
  `PAYMENT_GATEWAY_PROVIDER=stripe`.

- **Kubernetes** references secrets that are provisioned out-of-band:
  - `postgres-credentials` (`username`/`password`) — template in
    [`deploy/k8s/base/postgres/secret.example.yaml`](../deploy/k8s/base/postgres/secret.example.yaml);
  - `stripe-credentials` (`api-key`) — template in
    [`deploy/k8s/base/apps/payment-processor/stripe-secret.example.yaml`](../deploy/k8s/base/apps/payment-processor/stripe-secret.example.yaml);
    the reference is optional, so the default mock deployment needs nothing extra;
  - the Grafana admin login is added only by the local overlay.
