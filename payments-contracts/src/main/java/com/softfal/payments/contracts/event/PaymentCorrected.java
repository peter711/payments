package com.softfal.payments.contracts.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published to {@code payments.corrected} when a previously recorded
 * failure is superseded by a provider-confirmed success.
 *
 * <p>A correction is distinct from a first completion: the failure was already
 * announced, so downstream consumers must be told the outcome changed rather
 * than receiving an ordinary success.</p>
 *
 * @param previousStatus the status that was recorded before the correction
 * @param newStatus      the corrected status
 * @param correctedAt    when the correction was applied
 */
public record PaymentCorrected(
        String paymentId,
        String accountId,
        BigDecimal amount,
        String currency,
        String previousStatus,
        String newStatus,
        Instant correctedAt
) implements PaymentEvent {

    @Override
    public Instant occurredAt() {
        return correctedAt;
    }
}
