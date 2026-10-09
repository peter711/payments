package com.softfal.payment_processor.gateway;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * H2-backed {@link GatewayChargeStore}. The ledger is durable so it still holds
 * the original charge result after a restart, which is exactly the window that
 * causes double charges.
 */
@Repository
public class JdbcGatewayChargeStore implements GatewayChargeStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcGatewayChargeStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PaymentGatewayResult> find(String paymentId) {
        List<PaymentGatewayResult> results = jdbcTemplate.query("""
                        SELECT successful, reason
                        FROM gateway_charge
                        WHERE payment_id = ?
                        """,
                (rs, rowNum) -> rs.getBoolean("successful")
                        ? PaymentGatewayResult.success()
                        : PaymentGatewayResult.failure(rs.getString("reason")),
                paymentId);
        return results.stream().findFirst();
    }

    @Override
    public void save(String paymentId, PaymentGatewayResult result) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO gateway_charge (payment_id, successful, reason, charged_at)
                            VALUES (?, ?, ?, ?)
                            """,
                    paymentId,
                    result.successful(),
                    result.reason(),
                    Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException ex) {
            // Concurrent duplicate: the first recorded result is authoritative.
        }
    }
}
