-- Durable idempotency state for the payment processor.
--
-- processed_payment is the consumer-side inbox: one row per paymentId seen on
-- payments.requested. It doubles as an outcome cache so a redelivered record can
-- be answered with the original result instead of charging again.
CREATE TABLE IF NOT EXISTS processed_payment (
    payment_id   VARCHAR(64) PRIMARY KEY,
    account_id   VARCHAR(64),
    amount       DECIMAL(19, 2),
    currency     VARCHAR(8),
    status       VARCHAR(16) NOT NULL, -- IN_PROGRESS | COMPLETED | FAILED
    reason       VARCHAR(255),
    claimed_at   TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

-- gateway_charge is the gateway-side idempotency ledger. The mock gateway writes
-- one row per successfully or explicitly declined charge, keyed by paymentId.
-- In production this responsibility belongs to the payment provider, which
-- enforces the Idempotency-Key sent by the client.
CREATE TABLE IF NOT EXISTS gateway_charge (
    payment_id VARCHAR(64) PRIMARY KEY,
    successful BOOLEAN     NOT NULL,
    reason     VARCHAR(255),
    charged_at TIMESTAMP   NOT NULL
);

-- outbox holds outgoing events written in the same transaction as the outcome.
-- The scheduled relay publishes PENDING rows and stamps them PUBLISHED. Each
-- failed attempt bumps `attempts` and schedules `next_attempt_at` with backoff;
-- once `attempts` reaches the configured maximum the row is marked FAILED and no
-- longer retried automatically. The unique key makes enqueueing idempotent so a
-- redelivered input record cannot create a duplicate event.
CREATE TABLE IF NOT EXISTS outbox (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_id      VARCHAR(64)  NOT NULL,
    topic           VARCHAR(64)  NOT NULL,
    message_key     VARCHAR(64),
    payload         CLOB         NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING | PUBLISHED | FAILED
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(512),
    next_attempt_at TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL,
    published_at    TIMESTAMP,
    CONSTRAINT ux_outbox_payment_topic UNIQUE (payment_id, topic)
);

-- Migration for databases created before the status/attempts/backoff columns existed.
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0;
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS last_error VARCHAR(512);
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP;
