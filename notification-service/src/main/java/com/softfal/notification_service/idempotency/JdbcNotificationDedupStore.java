package com.softfal.notification_service.idempotency;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * H2-backed {@link NotificationDedupStore}. Durability matters because the
 * duplicate outcome usually arrives after a processor restart.
 */
@Repository
public class JdbcNotificationDedupStore implements NotificationDedupStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationDedupStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean markProcessed(String eventId, String paymentId, String topic) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO notification_log (event_id, payment_id, topic, notified_at)
                            VALUES (?, ?, ?, ?)
                            """,
                    eventId,
                    paymentId,
                    topic,
                    Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }
}
