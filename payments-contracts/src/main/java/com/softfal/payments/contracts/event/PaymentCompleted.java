package com.softfal.payments.contracts.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published to the {@code payments.completed} topic when the payment
 * gateway accepts a payment.
 */
public record PaymentCompleted(
        String paymentId,
        String accountId,
        BigDecimal amount,
        String currency,
        Instant completedAt
) implements PaymentEvent {

    @Override
    public Instant occurredAt() {
        return completedAt;
    }
}
