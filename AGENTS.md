# AGENTS.md

Event-driven payments platform: 5 Maven modules — 3 Spring Boot services and
2 shared libraries. Deep documentation lives in `README.md` and `docs/`; read
`docs/architecture.md` and `docs/development.md` before non-trivial changes.

## Commands

```bash
mvn -q -DskipTests package          # build the whole reactor (run from repo root)
mvn -q test                         # all tests
cd payment-processor && ./mvnw test # one module (wrapper ships only in the 3 services)
cd payment-processor && ./mvnw test -Dtest=PaymentProcessorTest  # one test class
```

- There is **no root `mvnw`**; reactor-wide commands need `mvn` on `PATH`.
- Tests need **no external services**: integration tests use `@EmbeddedKafka`,
  the stores are H2, and unit tests use in-memory doubles.

## Running the stack

```bash
docker compose up -d                                  # infra only; run services from IDE (.run/, ports 9080/9081/9082)
cp .env.example .env && docker compose -f docker-compose.full.yml up --build   # full stack
scripts/k8s/00-up.sh ... scripts/k8s/90-teardown.sh   # k3d two-replica stack
```

Compose requires `.env` (copy from `.env.example`).

## Layout and conventions

- `payments-contracts` (library) — Kafka topics, `EventEnvelope`, `EventType`
  registry, JSON Schemas. **Source of truth for the wire format.**
- `payments-observability` (library/starter) — shared Micrometer/Prometheus
  config, `LogContext`, shared YAML.
- `payment-api` → `payment-processor` → `notification-service` (runnable apps).
- Package roots are intentionally inconsistent — do not "normalize" them:
  `com.softfal.payment_api`, `com.softfal.payment_processor`,
  `com.softfal.notification_service`, but `com.softfal.payments.contracts` and
  `com.softfal.payments.observability`.
- **Jackson 3**, not 2: import `tools.jackson.databind.ObjectMapper`
  (never `com.fasterxml.jackson`). Injected where needed.
- **Never hard-code a Kafka topic** — use `PaymentTopics`.

## Gotchas

- **New event type:** add a payload record + versioned `*.v1.json` schema +
  an `EventType` enum constant + a contract-test sample. Never mutate a shipped
  schema version; add a new one instead.
- **Schemas are build-verified:** `payments-contracts/src/main/resources/schema`
  is validated against serialized events by `PaymentEventSchemaTest` — a field
  change without a schema update fails the build.
- **Persistence changes must edit both** `schema.sql` (H2) and
  `schema-postgres.sql` (Postgres / `k8s` profile).
- **Metrics stay low-cardinality:** never tag a meter with `paymentId`,
  `accountId` or `correlationId` — put those in logs via a `LogContext` scope.

## More detail

| Topic | Doc |
|---|---|
| Architecture, idempotency, outbox | `docs/architecture.md` |
| Build/test, conventions, adding events/metrics | `docs/development.md` |
| Profiles, properties, env vars, ports | `docs/configuration.md` |
| Demo scripts, failover, troubleshooting | `docs/operations.md` |
| k3d / kustomize deployment | `deploy/k8s/README.md` |
| Metrics, dashboards, alerts | `observability/README.md` |
