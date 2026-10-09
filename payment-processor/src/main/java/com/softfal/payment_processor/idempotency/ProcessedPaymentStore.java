package com.softfal.payment_processor.idempotency;

import com.softfal.payment_processor.model.PaymentOutcome;

import java.util.Optional;

/**
 * Durable store backing idempotent processing of {@code payments.requested}.
 *
 * <p>The store is keyed by {@code paymentId}. A record is first claimed (marked
 * {@code IN_PROGRESS}) and later completed with its {@link PaymentOutcome}. If
 * the same {@code paymentId} is delivered again — for example because the
 * process crashed before the consumer offset was committed — the stored outcome
 * can be replayed without calling the gateway a second time.</p>
 */
public interface ProcessedPaymentStore {

    /**
     * Returns the finalized outcome for the given payment, if one exists.
     */
    Optional<PaymentOutcome> findFinal(String paymentId);

    /**
     * Atomically records that processing has started for the given payment.
     * Calling it more than once for the same payment is a no-op.
     */
    void claim(String paymentId);

    /**
     * Persists the final outcome for the given payment.
     */
    void complete(PaymentOutcome outcome);
}
