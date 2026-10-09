package com.softfal.payment_processor.outbox;

import com.softfal.payment_processor.model.PaymentOutcome;

/**
 * Persists a finalized {@link PaymentOutcome}: the idempotency record and the
 * outgoing outbox event must be written atomically, so a committed outcome is
 * always paired with an event that the {@link PaymentOutboxRelay} can publish.
 *
 * <p>The tracing context of the incoming event is passed through so the outgoing
 * event can carry the same {@code correlationId} and link back to its cause.</p>
 */
public interface PaymentOutcomeRecorder {

    /**
     * @param outcome       the finalized payment outcome
     * @param correlationId correlation id of the business flow
     * @param causationId   id of the incoming event that caused this outcome
     */
    void record(PaymentOutcome outcome, String correlationId, String causationId);
}
