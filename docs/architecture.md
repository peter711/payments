# Architecture

## Components

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

| Component | Role |
|---|---|
| **payment-api** | Maps the HTTP request to the wire contract, ensures a `paymentId`, wraps the event in an envelope and publishes it to `payments.requested`, keyed by `accountId`. |
| **payment-processor** | Consumes `payments.requested`, validates, deduplicates, calls the payment gateway and records the outcome + outgoing event with a transactional outbox. |
| **notification-service** | Consumes `payments.completed` and `payments.failed`, deduplicates per `(paymentId, topic)` and forwards to a notification sender. |
| **Kafka** | The backbone: four topics, three partitions each (local, RF=1). |
| **Database** | Durable state. H2 file in local dev, shared PostgreSQL in the clustered profile. |

## Topics

Defined once in `payments-contracts` (`PaymentTopics`), never as string literals.

| Topic | Produced by | Consumed by |
|---|---|---|
| `payments.requested` | payment-api | payment-processor |
| `payments.completed` | payment-processor (outbox) | notification-service |
| `payments.failed` | payment-processor (outbox) | notification-service |
| `payments.requested.DLT` | payment-processor (error handler) | (operators / tooling) |

## Event envelope

Every record on the wire is an `EventEnvelope`, so consumers can read its
identity and tracing context without knowing the domain type up front:

| Field | Meaning |
|---|---|
| `eventId` | Unique event id; the deduplication key. |
| `eventType` | Logical name, e.g. `payment.completed`. |
| `eventVersion` | Schema version of the payload. |
| `occurredAt` | Domain timestamp copied from the payload. |
| `correlationId` | Ties together every event of one business flow. |
| `causationId` | Id of the event that caused this one (`null` for the root). |
| `payload` | The serialized domain event (a Jackson `JsonNode`). |

The payload is carried as a `JsonNode` so the envelope stays non-generic and can
be deserialized by a plain Jackson deserializer. `EventEnvelopes.wrap(...)` and
`EventEnvelopes.payload(...)` are the shared helpers used by producers and
consumers alike.

### Event types and schemas

`EventType` binds each event to its topic, logical name, version and JSON Schema:

| Event | Topic | Version | Schema |
|---|---|---|---|
| `payment.requested` | `payments.requested` | 1 | `payment.requested.v1.json` |
| `payment.completed` | `payments.completed` | 1 | `payment.completed.v1.json` |
| `payment.failed` | `payments.failed` | 1 | `payment.failed.v1.json` |

The schemas live in `payments-contracts/src/main/resources/schema`. A build-time
test validates sample events against them, so the wire format cannot drift
silently. Adding an event or a new version is described in
[development.md](development.md#adding-an-event-type).

## End-to-end flow

1. **Submit.** `POST /payments` with an optional `paymentId` and optional
   `X-Correlation-Id` header. A missing `paymentId` is generated; a missing
   correlation id falls back to the `paymentId`. The API replies `202 Accepted`
   with the accepted payload and publishes `payment.requested` (key = `accountId`).
2. **Process.** `payment-processor` validates the event (blank ids, non-positive
   amount → permanent failure). It checks its durable inbox; if the payment
   already reached a final state it skips the gateway. Otherwise it claims the
   payment, calls the gateway, and records the outcome.
3. **Publish.** The outcome and its `payment.completed`/`payment.failed` event are
   written in **one transaction** into the outbox. A scheduled relay picks up
   `PENDING` rows and publishes them to Kafka.
4. **Notify.** `notification-service` consumes the outcome, deduplicates per
   `(paymentId, topic)` and hands it to the notification sender.

## Idempotency at three levels

Kafka redelivers on retries, rebalances and restarts; the pipeline is safe
because each hop deduplicates against durable state.

1. **Gateway ledger (`gateway_charge`).** The mock gateway records one row per
   `paymentId`; repeating a charge returns the stored result instead of charging
   again. In production this is the payment provider's job, keyed by an
   `Idempotency-Key`.
2. **Processor inbox (`processed_payment`).** Before calling the gateway the
   processor looks for a final outcome and, if absent, `INSERT`s an `IN_PROGRESS`
   row (a unique-key claim). A redelivered record whose outcome is known is
   skipped.
3. **Notification log (`notification_log`).** The notification service inserts a
   row keyed by `(payment_id, topic)`; a duplicate outcome is skipped.

The outbox also has a unique key `(payment_id, topic)`, so enqueueing the same
event twice is a no-op rather than a duplicate.

## Transactional outbox

Writing to the database and publishing to Kafka are two different systems; the
outbox closes the gap between them.

- The `PaymentOutcomeRecorder` writes the finalized outcome and the outgoing
  event in a **single database transaction**. If the commit succeeds the event is
  guaranteed to be stored; if it fails, neither is stored and the input record
  can be retried safely.
- The `PaymentOutboxRelay` polls `PENDING` rows on a fixed delay, publishes them,
  and marks each `PUBLISHED` only after the broker acknowledges.
- Failures bump `attempts`, schedule `next_attempt_at` with exponential backoff
  and, after `max-attempts`, mark the row `FAILED` so it stops retrying.
- Published rows older than the retention window are purged periodically.

Delivery is **at-least-once**: a crash between publishing and marking may
republish an event, which downstream consumers absorb idempotently.

### Single instance vs. cluster

`JdbcOutboxStore` adapts to the database:

| Database | Claim strategy |
|---|---|
| **H2** (local dev, one relay) | Plain read of the oldest due `PENDING` rows. |
| **PostgreSQL** (cluster, two relays) | `UPDATE ... FOR UPDATE SKIP LOCKED` with a 60 s lease, so a second relay skips rows another instance owns. |

## Error handling and the dead-letter topic

The `payments.requested` listener uses a `DefaultErrorHandler` with a fixed
backoff (1 s, 2 retries) and a `DeadLetterPublishingRecoverer`:

- **Transient failures** (e.g. gateway timeouts) are retried; the flaky account
  times out twice and then succeeds.
- **Permanent failures** (`InvalidPaymentException`: malformed payload, blank id,
  non-positive amount) are marked non-retryable and go **straight** to
  `payments.requested.DLT`.
- A record that keeps failing exhausts the retries and is dead-lettered too.

Deserialization failures keep the original bytes (routed through a raw `byte[]`
producer template) so the dead-letter payload stays intact.

## Mock components

The external systems are mocked so the pipeline runs with no third parties:

| Mock | Behaviour |
|---|---|
| `MockPaymentGateway` | ~80 % success (configurable), ~200 ms latency; account `ACC-FLAKY` times out twice then succeeds, `ACC-TIMEOUT` always times out. |
| `MockNotificationSender` | Logs the notification instead of calling a provider. |

## Data model

**payment-processor** (`schema.sql` / `schema-postgres.sql`)

| Table | Purpose |
|---|---|
| `processed_payment` | Consumer inbox / outcome cache, PK `payment_id`, status `IN_PROGRESS`/`COMPLETED`/`FAILED`. |
| `gateway_charge` | Gateway idempotency ledger, PK `payment_id`. |
| `outbox` | Outgoing events, unique `(payment_id, topic)`, status `PENDING`/`PUBLISHED`/`FAILED`, `attempts`, `next_attempt_at`. |

**notification-service**

| Table | Purpose |
|---|---|
| `notification_log` | Deduplication log, PK `(payment_id, topic)`. |
