package com.softfal.payments.contracts.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain event describing a payment status reported by the external payment
 * provider. It is published by the webhook edge and consumed by the processor,
 * which reconciles the local payment state against it.
 *
 * <p>The payload is the domain view of the provider notification, not the raw
 * provider payload, so consumers never depend on a specific provider SDK.</p>
 *
 * @param paymentId       the local payment the update belongs to
 * @param accountId       the account that owns the payment
 * @param amount          the payment amount in major units
 * @param currency        ISO currency code
 * @param providerStatus  the provider's reported status
 * @param providerEventId the provider's own event id, kept for observability
 * @param reason          failure reason when the provider supplied one, else null
 * @param occurredAt      when the provider reported the update
 */
public record PaymentProviderEvent(
        String paymentId,
        String accountId,
        BigDecimal amount,
        String currency,
        ProviderPaymentStatus providerStatus,
        String providerEventId,
        String reason,
        Instant occurredAt
) implements PaymentEvent {
}
