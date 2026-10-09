package com.softfal.payment_api.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Shape of the event published to Kafka.
 */
public record PaymentEventRequest(
        String paymentId,
        String accountId,
        BigDecimal amount,
        String currency,
        Instant createdAt
) {
}
