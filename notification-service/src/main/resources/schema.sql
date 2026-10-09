-- Durable dedup state for notifications.
--
-- The processor can legitimately publish the same outcome more than once (for
-- example when it replays a stored outcome after a crash). The notification
-- service records every paymentId it has notified about so a repeated outcome
-- does not produce a second notification. The originating event id is kept for
-- observability, while (payment_id, topic) remains the deduplication key.
CREATE TABLE IF NOT EXISTS notification_log (
    event_id    VARCHAR(64),
    payment_id  VARCHAR(64) NOT NULL,
    topic       VARCHAR(64) NOT NULL,
    notified_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (payment_id, topic)
);

-- Migration for databases created before the event_id column existed.
ALTER TABLE notification_log ADD COLUMN IF NOT EXISTS event_id VARCHAR(64);
