package com.softfal.payments.contracts.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published to the {@code payments.requested} topic when a new payment is
 * submitted to the system.
 */
public record PaymentRequested(
        String paymentId,
        String accountId,
        BigDecimal amount,
        String currency,
        Instant createdAt
) implements PaymentEvent {

    @Override
    public Instant occurredAt() {
        return createdAt;
    }
}
