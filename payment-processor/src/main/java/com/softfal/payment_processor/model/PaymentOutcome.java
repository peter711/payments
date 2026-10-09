package com.softfal.payment_processor.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Final outcome of processing a {@link com.softfal.payments.contracts.event.PaymentRequested}, persisted before it is
 * published so a redelivered record can be answered deterministically.
 *
 * @param successful whether the gateway accepted the payment
 * @param reason     failure reason, {@code null} on success
 */
public record PaymentOutcome(
        String paymentId,
        String accountId,
        BigDecimal amount,
        String currency,
        boolean successful,
        String reason,
        Instant completedAt
) {
}
