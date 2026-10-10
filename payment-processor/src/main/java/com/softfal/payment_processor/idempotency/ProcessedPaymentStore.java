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
     * Persists the final outcome for the given payment, advancing its status only
     * if the new status ranks higher ({@code IN_PROGRESS < FAILED < COMPLETED}).
     * A lower- or equal-ranked write is a no-op, so a slow synchronous write can
     * never clobber a provider-confirmed reconciliation (or vice versa).
     *
     * @return {@code true} if the state advanced, {@code false} if it was a no-op
     */
    boolean complete(PaymentOutcome outcome);
}
