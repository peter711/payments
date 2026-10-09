-- PostgreSQL schema for notification-service, used by the clustered (k8s) profile.
--
-- Mirrors the H2 src/main/resources/schema.sql but drops the H2-only ALTER
-- migration, which is unnecessary for a freshly created database.
CREATE TABLE IF NOT EXISTS notification_log (
    event_id    VARCHAR(64),
    payment_id  VARCHAR(64) NOT NULL,
    topic       VARCHAR(64) NOT NULL,
    notified_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (payment_id, topic)
);
