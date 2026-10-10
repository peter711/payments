package com.softfal.notification_service.idempotency;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * H2-backed {@link NotificationDedupStore}. Durability matters because the
 * duplicate outcome usually arrives after a processor restart.
 *
 * <p>The announcement is guarded by a conditional insert: a row is written only
 * when no higher- or equal-ranked outcome exists for the payment, so the check
 * and the write happen in one statement and two listeners cannot both announce
 * the same rank for a payment.</p>
 */
@Repository
public class JdbcNotificationDedupStore implements NotificationDedupStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationDedupStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean markProcessed(String eventId, String paymentId, String topic, int outcomeRank) {
        try {
            int inserted = jdbcTemplate.update("""
                            INSERT INTO notification_log (event_id, payment_id, topic, outcome_rank, notified_at)
                            SELECT ?, ?, ?, ?, ?
                            FROM (SELECT 1) AS probe
                            WHERE NOT EXISTS (
                                SELECT 1 FROM notification_log WHERE payment_id = ? AND outcome_rank >= ?
                            )
                            """,
                    eventId,
                    paymentId,
                    topic,
                    outcomeRank,
                    Timestamp.from(Instant.now()),
                    paymentId,
                    outcomeRank);
            return inserted > 0;
        } catch (DuplicateKeyException ex) {
            // The same (paymentId, topic) was already recorded.
            return false;
        }
    }
}
