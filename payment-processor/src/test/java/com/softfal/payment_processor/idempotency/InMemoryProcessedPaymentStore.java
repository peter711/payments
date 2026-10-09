package com.softfal.payment_processor.idempotency;

import com.softfal.payment_processor.model.PaymentOutcome;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ProcessedPaymentStore} for unit tests: it keeps finalized
 * outcomes so duplicate/redelivery behaviour can be exercised without H2.
 */
public class InMemoryProcessedPaymentStore implements ProcessedPaymentStore {

    private final ConcurrentHashMap<String, PaymentOutcome> outcomes = new ConcurrentHashMap<>();

    @Override
    public Optional<PaymentOutcome> findFinal(String paymentId) {
        return Optional.ofNullable(outcomes.get(paymentId));
    }

    @Override
    public void claim(String paymentId) {
        // No-op: only finalized outcomes are exposed by findFinal.
    }

    @Override
    public void complete(PaymentOutcome outcome) {
        outcomes.put(outcome.paymentId(), outcome);
    }
}
