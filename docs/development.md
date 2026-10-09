# Development

## Reactor layout

Five Maven modules; two are libraries and three are runnable Spring Boot apps.

```
payments-contracts ─┐
                    ├─▶ payment-api
payments-observability ─┬─▶ payment-processor
                        └─▶ notification-service
```

| Package root | Module |
|---|---|
| `com.softfal.payments.contracts` | payments-contracts |
| `com.softfal.payments.observability` | payments-observability |
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
| payment-api | `payments.api.requests{result=accepted}`, `payments.api.publish.errors` |
| payment-processor | `payments.processor.outcomes{result}`, `payments.processor.duplicates`, `payments.processor.gateway.duration`, `payments.processor.outbox.published`, `payments.processor.outbox.publish.failures`, `payments.processor.outbox.pending` (gauge), `payments.processor.dlq` |
| notification-service | `notifications.sent{result}`, `notifications.duplicates` |

The `payments-observability` module already attaches the common `application` and
`env` tags, so new meters inherit them automatically.

## Testing

- Integration tests use `@EmbeddedKafka` — no external broker required.
- Unit tests use in-memory doubles for the stores
  (`InMemoryProcessedPaymentStore`, `InMemoryOutboxStore`,
  `InMemoryGatewayChargeStore`), e.g. `MockPaymentGatewayTest`,
  `PaymentOutboxRelayTest`, `PaymentProcessorTest`.
- The contracts module validates sample payloads against the JSON Schemas with
  `json-schema-validator`.
- Prometheus exposure is covered by `PrometheusEndpointTests` in payment-api.

## JSON handling

The services use **Jackson 3** (`tools.jackson.databind.ObjectMapper`), injected
where needed. `payments-contracts` carries its payload as a Jackson `JsonNode` so
the envelope stays non-generic.
