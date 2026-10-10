package com.softfal.payment_processor.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentOutcomeTest {

    @Test
    void truncatesReasonLongerThanThePersistenceColumn() {
        PaymentOutcome outcome = new PaymentOutcome(
                "pay-1", "acc-1", new BigDecimal("10.00"), "PLN", false, "x".repeat(492), Instant.now());

        assertThat(outcome.reason()).hasSize(255).endsWith("...");
    }

    @Test
    void keepsShortReasonUntouched() {
        PaymentOutcome outcome = new PaymentOutcome(
                "pay-1", "acc-1", new BigDecimal("10.00"), "PLN", false, "declined", Instant.now());

        assertThat(outcome.reason()).isEqualTo("declined");
    }

    @Test
    void acceptsNullReason() {
        PaymentOutcome outcome = new PaymentOutcome(
                "pay-1", "acc-1", new BigDecimal("10.00"), "PLN", true, null, Instant.now());

        assertThat(outcome.reason()).isNull();
    }
}
