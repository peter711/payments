package com.softfal.payment_processor.idempotency;

import com.softfal.payment_processor.model.PaymentOutcome;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * H2-backed {@link ProcessedPaymentStore}. The data lives on disk so the
 * idempotency state survives the process restarts that make duplicate delivery
 * dangerous in the first place.
 */
@Repository
public class JdbcProcessedPaymentStore implements ProcessedPaymentStore {

    private static final String IN_PROGRESS = "IN_PROGRESS";

    private final JdbcTemplate jdbcTemplate;

    public JdbcProcessedPaymentStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PaymentOutcome> findFinal(String paymentId) {
        List<PaymentOutcome> outcomes = jdbcTemplate.query("""
                        SELECT payment_id, account_id, amount, currency, status, reason, completed_at
                        FROM processed_payment
                        WHERE payment_id = ? AND status <> ?
                        """,
                (rs, rowNum) -> map(rs),
                paymentId,
                IN_PROGRESS);
        return outcomes.stream().findFirst();
    }

    @Override
    public void claim(String paymentId) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO processed_payment (payment_id, status, claimed_at)
                            VALUES (?, ?, ?)
                            """,
                    paymentId,
                    IN_PROGRESS,
                    Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException ex) {
            // Already claimed by an earlier delivery or a concurrent thread.
        }
    }

    @Override
    public boolean complete(PaymentOutcome outcome) {
        int updated = jdbcTemplate.update("""
                        UPDATE processed_payment
                        SET account_id = ?, amount = ?, currency = ?, status = ?, reason = ?, completed_at = ?
                        WHERE payment_id = ?
                          AND (CASE ? WHEN 'IN_PROGRESS' THEN 0 WHEN 'FAILED' THEN 1 WHEN 'COMPLETED' THEN 2 ELSE 0 END)
                              > (CASE status WHEN 'IN_PROGRESS' THEN 0 WHEN 'FAILED' THEN 1 WHEN 'COMPLETED' THEN 2 ELSE 0 END)
                        """,
                outcome.accountId(),
                outcome.amount(),
                outcome.currency(),
                outcome.successful() ? "COMPLETED" : "FAILED",
                outcome.reason(),
                Timestamp.from(outcome.completedAt()),
                outcome.paymentId(),
                outcome.successful() ? "COMPLETED" : "FAILED");
        return updated > 0;
    }

    private static PaymentOutcome map(ResultSet rs) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new PaymentOutcome(
                rs.getString("payment_id"),
                rs.getString("account_id"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                "COMPLETED".equals(rs.getString("status")),
                rs.getString("reason"),
                completedAt == null ? null : completedAt.toInstant());
    }
}
