package com.softfal.payments.contracts.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published to the {@code payments.failed} topic when the payment gateway
 * rejects a payment.
 */
public record PaymentFailed(
        String paymentId,
        String accountId,
        BigDecimal amount,
        String currency,
        String reason,
        Instant failedAt
) implements PaymentEvent {

    @Override
    public Instant occurredAt() {
        return failedAt;
    }
}
