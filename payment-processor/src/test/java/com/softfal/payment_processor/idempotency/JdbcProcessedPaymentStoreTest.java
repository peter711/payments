package com.softfal.payment_processor.idempotency;

import com.softfal.payment_processor.model.PaymentOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcProcessedPaymentStoreTest {

    private JdbcProcessedPaymentStore store;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:proc-store-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbcTemplate.execute("""
                CREATE TABLE processed_payment (
                    payment_id   VARCHAR(64) PRIMARY KEY,
                    account_id   VARCHAR(64),
                    amount       DECIMAL(19, 2),
                    currency     VARCHAR(8),
                    status       VARCHAR(16) NOT NULL,
                    reason       VARCHAR(255),
                    claimed_at   TIMESTAMP NOT NULL,
                    completed_at TIMESTAMP
                )""");
        store = new JdbcProcessedPaymentStore(jdbcTemplate);
    }

    @Test
    void lowerRankedWriteIsNoOp() {
        store.claim("pay-1");

        assertThat(store.complete(outcome("pay-1", true))).isTrue();
        assertThat(store.complete(outcome("pay-1", false))).isFalse();

        assertThat(store.findFinal("pay-1")).get()
                .extracting(PaymentOutcome::successful)
                .isEqualTo(true);
    }

    @Test
    void higherRankedWriteAdvancesAFailureToSuccess() {
        store.claim("pay-2");

        assertThat(store.complete(outcome("pay-2", false))).isTrue();
        assertThat(store.findFinal("pay-2")).get()
                .extracting(PaymentOutcome::successful)
                .isEqualTo(false);
        assertThat(store.complete(outcome("pay-2", true))).isTrue();
        assertThat(store.findFinal("pay-2")).get()
                .extracting(PaymentOutcome::successful)
                .isEqualTo(true);
    }

    @Test
    void equalRankedWriteIsNoOp() {
        store.claim("pay-3");
        store.complete(outcome("pay-3", true));

        assertThat(store.complete(outcome("pay-3", true))).isFalse();
    }

    private static PaymentOutcome outcome(String paymentId, boolean successful) {
        return new PaymentOutcome(paymentId, "acc-1", new BigDecimal("10.00"), "PLN", successful,
                successful ? null : "declined", Instant.parse("2026-01-01T00:00:00Z"));
    }
}
