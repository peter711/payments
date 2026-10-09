package com.softfal.payment_processor.gateway;

import java.util.Optional;

/**
 * Idempotency ledger for the payment gateway, keyed by {@code paymentId}.
 *
 * <p>A real provider would own this store and enforce an {@code Idempotency-Key}
 * header, guaranteeing that repeating a charge request returns the original
 * result instead of moving money twice. The mock keeps the same contract against
 * a durable store.</p>
 */
public interface GatewayChargeStore {

    /**
     * Returns the result of a previous charge for the payment, if any.
     */
    Optional<PaymentGatewayResult> find(String paymentId);

    /**
     * Records the result of a charge. Recording the same payment twice is a no-op
     * so the first result wins.
     */
    void save(String paymentId, PaymentGatewayResult result);
}
