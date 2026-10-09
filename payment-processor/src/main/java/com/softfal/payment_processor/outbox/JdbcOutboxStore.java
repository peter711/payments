package com.softfal.payment_processor.outbox;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * JDBC-backed {@link OutboxStore}.
 *
 * <p>The two supported databases need different claiming strategies:</p>
 * <ul>
 *   <li><b>H2</b> (local development, a single relay instance) selects PENDING
 *       rows directly, exactly as before.</li>
 *   <li><b>PostgreSQL</b> (clustered deployment, two relay instances) claims rows
 *       with {@code FOR UPDATE SKIP LOCKED} and leases them by pushing
 *       {@code next_attempt_at} into the future, so the second relay can never
 *       pick up the same row. A crash mid-publish simply lets the lease expire and
 *       the row is retried (at-least-once, which downstream consumers absorb).</li>
 * </ul>
 */
@Repository
public class JdbcOutboxStore implements OutboxStore {

    /**
     * Lease applied when a row is claimed: how long it stays invisible to other
     * relays while it is being published. Longer than the relay's publish timeout
     * (10s) so a healthy publish always completes before the lease expires.
     */
    private static final long CLAIM_LEASE_SECONDS = 60L;

    private static final RowMapper<OutboxEvent> OUTBOX_ROW_MAPPER = (rs, rowNum) -> new OutboxEvent(
            rs.getLong("id"),
            rs.getString("payment_id"),
            rs.getString("topic"),
            rs.getString("message_key"),
            rs.getString("payload"),
            rs.getInt("attempts"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;
    private final boolean postgres;

    public JdbcOutboxStore(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.postgres = isPostgres(dataSource);
    }

    @Override
    public boolean enqueue(String paymentId, String topic, String messageKey, String payload) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO outbox (payment_id, topic, message_key, payload, status, attempts, created_at)
                            VALUES (?, ?, ?, ?, 'PENDING', 0, ?)
                            """,
                    paymentId,
                    topic,
                    messageKey,
                    payload,
                    Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public List<OutboxEvent> findPublishable(int limit) {
        return postgres ? findPublishablePostgres(limit) : findPublishableSingleInstance(limit);
    }

    /**
     * Single-instance variant for H2: plain read of the oldest due rows.
     */
    private List<OutboxEvent> findPublishableSingleInstance(int limit) {
        return jdbcTemplate.query("""
                        SELECT id, payment_id, topic, message_key, payload, attempts, created_at
                        FROM outbox
                        WHERE status = 'PENDING'
                          AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                        ORDER BY id
                        LIMIT ?
                        """,
                OUTBOX_ROW_MAPPER,
                Timestamp.from(Instant.now()),
                limit);
    }

    /**
     * Multi-instance variant for PostgreSQL: atomically claims due rows, leases
     * them and returns them for publishing. {@code SKIP LOCKED} makes concurrent
     * relays step over rows another instance already owns.
     */
    private List<OutboxEvent> findPublishablePostgres(int limit) {
        return jdbcTemplate.query("""
                        UPDATE outbox
                        SET next_attempt_at = now() + make_interval(secs => ?)
                        WHERE id IN (
                            SELECT id
                            FROM outbox
                            WHERE status = 'PENDING'
                              AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                            ORDER BY id
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                        )
                        RETURNING id, payment_id, topic, message_key, payload, attempts, created_at
                        """,
                OUTBOX_ROW_MAPPER,
                (double) CLAIM_LEASE_SECONDS,
                limit);
    }

    @Override
    public long countPending() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'", Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public void markPublished(long id) {
        jdbcTemplate.update("""
                        UPDATE outbox
                        SET status = 'PUBLISHED', published_at = ?, next_attempt_at = NULL, last_error = NULL
                        WHERE id = ?
                        """,
                Timestamp.from(Instant.now()), id);
    }

    @Override
    public void markFailed(long id, String error, Instant nextAttemptAt, int maxAttempts) {
        jdbcTemplate.update("""
                        UPDATE outbox
                        SET attempts = attempts + 1,
                            last_error = ?,
                            next_attempt_at = ?,
                            status = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END
                        WHERE id = ?
                        """,
                error,
                Timestamp.from(nextAttemptAt),
                maxAttempts,
                id);
    }

    @Override
    public int deletePublishedBefore(Instant threshold) {
        return jdbcTemplate.update(
                "DELETE FROM outbox WHERE status = 'PUBLISHED' AND published_at < ?",
                Timestamp.from(threshold));
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not detect the database product name", ex);
        }
    }
}
