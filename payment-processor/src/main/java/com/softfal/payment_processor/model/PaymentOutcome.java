package com.softfal.payment_processor.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Final outcome of processing a {@link com.softfal.payments.contracts.event.PaymentRequested}, persisted before it is
 * published so a redelivered record can be answered deterministically.
 *
 * @param successful whether the gateway accepted the payment
 * @param reason     failure reason, {@code null} on success; bounded to the
 *                   persistence column width so a chatty provider cannot break
 *                   the outcome write
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

    /** Matches the {@code reason} column width in schema.sql / schema-postgres.sql. */
    private static final int MAX_REASON_LENGTH = 255;

    public PaymentOutcome {
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH - 3) + "...";
        }
    }
}
