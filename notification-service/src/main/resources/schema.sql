-- Durable dedup state for notifications.
--
-- The processor can legitimately publish the same outcome more than once (for
-- example when it replays a stored outcome after a crash). The notification
-- service records the highest-ranked outcome it has announced per payment so a
-- repeated outcome does not produce a second notification, and a lower-ranked
-- outcome arriving later (the outcome topics have no relative order) is
-- suppressed. The originating event id is kept for observability, while
-- (payment_id, topic) remains the physical deduplication key.
CREATE TABLE IF NOT EXISTS notification_log (
    event_id     VARCHAR(64),
    payment_id   VARCHAR(64) NOT NULL,
    topic        VARCHAR(64) NOT NULL,
    outcome_rank INT         NOT NULL DEFAULT 0,
    notified_at  TIMESTAMP   NOT NULL,
    PRIMARY KEY (payment_id, topic)
);

-- Migration for databases created before these columns existed.
ALTER TABLE notification_log ADD COLUMN IF NOT EXISTS event_id VARCHAR(64);
ALTER TABLE notification_log ADD COLUMN IF NOT EXISTS outcome_rank INT NOT NULL DEFAULT 0;
