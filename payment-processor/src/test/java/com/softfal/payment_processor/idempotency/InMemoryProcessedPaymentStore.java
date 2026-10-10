package com.softfal.payment_processor.idempotency;

import com.softfal.payment_processor.model.PaymentOutcome;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ProcessedPaymentStore} for unit tests. It mirrors the durable
 * store's monotonic behaviour: a payment is claimed as {@code IN_PROGRESS} and an
 * outcome only advances the status ({@code IN_PROGRESS < FAILED < COMPLETED}).
 */
public class InMemoryProcessedPaymentStore implements ProcessedPaymentStore {

    private static final int IN_PROGRESS = 0;
    private static final int FAILED = 1;
    private static final int COMPLETED = 2;

    private final ConcurrentHashMap<String, Integer> statuses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PaymentOutcome> outcomes = new ConcurrentHashMap<>();

    @Override
    public Optional<PaymentOutcome> findFinal(String paymentId) {
        Integer rank = statuses.get(paymentId);
        if (rank == null || rank == IN_PROGRESS) {
            return Optional.empty();
        }
        return Optional.ofNullable(outcomes.get(paymentId));
    }

    @Override
    public void claim(String paymentId) {
        statuses.putIfAbsent(paymentId, IN_PROGRESS);
    }

    @Override
    public synchronized boolean complete(PaymentOutcome outcome) {
        int target = outcome.successful() ? COMPLETED : FAILED;
        int current = statuses.getOrDefault(outcome.paymentId(), IN_PROGRESS);
        if (target <= current) {
            return false;
        }
        statuses.put(outcome.paymentId(), target);
        outcomes.put(outcome.paymentId(), outcome);
        return true;
    }
}
