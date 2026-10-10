# Development

## Reactor layout

Six Maven modules; three are libraries and three are runnable Spring Boot apps.

```
payments-contracts ──┬─▶ payment-api
                     ├─▶ payments-gateway ──▶ stripe-java
                     └─▶ payment-processor

payments-observability ─┬─▶ payment-api
                        ├─▶ payment-processor
                        └─▶ notification-service

payment-processor also depends on payments-gateway (the PaymentGateway port
and the Stripe adapter).
```

| Package root | Module |
|---|---|
| `com.softfal.payments.contracts` | payments-contracts |
| `com.softfal.payments.observability` | payments-observability |
| `com.softfal.payments.gateway` | payments-gateway |
| `com.softfal.payment_api` | payment-api |
| `com.softfal.payment_processor` | payment-processor |
| `com.softfal.notification_service` | notification-service |

Packages are organised by feature (`controller`, `service`, `listener`,
`gateway`, `idempotency`, `outbox`, `metrics`), constructor injection is used
throughout, and domain types are Java 17 records.

## Build and test

```bash
mvn -q -DskipTests package     # whole reactor
mvn -q test                    # whole reactor
cd payment-processor && ./mvnw test   # a single module via its wrapper
```

## Conventions

- **Topics are constants.** Never hard-code a topic string; use
  `PaymentTopics` in `payments-contracts`.
- **Everything is enveloped.** Producers wrap domain events with
  `EventEnvelopes.wrap(...)`; consumers unwrap with `EventEnvelopes.payload(...)`.
- **`EventType` is the registry.** A new event means a new enum constant binding
  topic, name, version, class and schema resource.
- **Schemas are versioned and validated.** Payload schemas live under
  `payments-contracts/src/main/resources/schema` and are checked by a build-time
  test, so the wire format cannot drift silently.
- **Metrics stay low-cardinality.** Never tag a meter with `paymentId`,
  `accountId`, `correlationId` or any unbounded value — those belong in logs.
  Metrics are named in dot notation in code (`payments.processor.outcomes`) and
  surface in Prometheus as snake_case (`payments_processor_outcomes_total`).
- **Logs carry the correlation.** Open a `LogContext` scope (`payments-observability`)
  for the duration of a unit of work and chain the ids you know. Spring Boot emits
  them as flat ECS JSON fields, and Alloy maps them to Loki structured metadata.
- **Configuration as code.** Dashboards, datasources and alerts live in git
  under `observability/` and are provisioned, never clicked together in the UI.
- **Two schemas stay in sync.** A persistence change must be reflected in both
  `schema.sql` (H2) and `schema-postgres.sql` (PostgreSQL).

## Adding an event type

1. Add a record implementing `PaymentEvent` (its `occurredAt()` feeds the
   envelope timestamp) in `payments-contracts/.../event`.
2. Add the matching payload JSON Schema under `resources/schema`, following the
   `*.v1.json` naming.
3. Register it in `EventType` (topic, logical name, version, class, schema
   resource). `fromTopic` / `fromType` resolution then works everywhere.
4. Extend the contract tests in `payments-contracts` with a valid (and, ideally,
   an invalid) sample so the schema is exercised.

To evolve an existing event, add a new schema version and a new `EventType`
constant (or a versioned payload) rather than mutating `v1` in place.

## Adding a business metric

Inject `MeterRegistry` and register a `Counter`, `Timer` or `Gauge` with a
bounded tag set. See `PaymentApiMetrics`, `PaymentProcessorMetrics` and
`NotificationMetrics` for the pattern.

| Service | Metrics |
|---|---|
| payment-api | `payments.api.requests{result=accepted}`, `payments.api.publish.errors`, `payments.api.webhook.received{result}` |
| payment-processor | `payments.processor.outcomes{result}`, `payments.processor.duplicates`, `payments.processor.gateway.duration`, `payments.processor.outbox.published`, `payments.processor.outbox.publish.failures`, `payments.processor.outbox.pending` (gauge), `payments.processor.dlq`, `payments.processor.corrections` |
| notification-service | `notifications.sent{result}`, `notifications.duplicates`, `notifications.corrections` |

The `payments-observability` module already attaches the common `application` and
`env` tags, so new meters inherit them automatically.

## Adding log context

Wrap a unit of work in a `LogContext` scope and chain the fields that are known
at that point; the scope restores the previous MDC values on close, so it nests
safely across a request handler calling a service.

```java
try (var logContext = LogContext.scope()
        .correlationId(envelope.correlationId())
        .paymentId(event.paymentId())) {
    log.info("Processing payment");
}
```

| Key | Source |
|---|---|
| `correlation_id` | `EventEnvelope.correlationId()` (or `X-Correlation-Id`) |
| `causation_id` | `EventEnvelope.causationId()` |
| `event_id`, `event_type` | `EventEnvelope` |
| `payment_id`, `account_id` | the domain event |
| `topic` | the Kafka topic / handler |

Keep keys flat and snake_case so they do not clash with the typed ECS field
names. The `RecordInterceptor`-free design means every service owns its scopes;
see `PaymentController`/`PaymentEventService`, `PaymentProcessor`,
`PaymentOutboxRelay` and `NotificationListener` for examples.

## Testing

- Integration tests use `@EmbeddedKafka` — no external broker required.
- Unit tests use in-memory doubles for the stores
  (`InMemoryProcessedPaymentStore`, `InMemoryOutboxStore`,
  `InMemoryGatewayChargeStore`), e.g. `MockPaymentGatewayTest`,
  `PaymentOutboxRelayTest`, `PaymentProcessorTest`.
- The Stripe adapter is driven by WireMock against a fake `api.stripe.com`
  (`StripePaymentGatewayTest` in `payments-gateway`), so no sandbox account or
  network access is needed.
- The contracts module validates sample payloads against the JSON Schemas with
  `json-schema-validator`.
- Prometheus exposure is covered by `PrometheusEndpointTests` in payment-api.

## JSON handling

The services use **Jackson 3** (`tools.jackson.databind.ObjectMapper`), injected
where needed. `payments-contracts` carries its payload as a Jackson `JsonNode` so
the envelope stays non-generic.
