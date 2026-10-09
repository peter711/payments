package com.softfal.payment_processor.gateway;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link GatewayChargeStore} for unit tests. It reproduces the
 * idempotency contract (first result wins) without a database.
 */
class InMemoryGatewayChargeStore implements GatewayChargeStore {

    private final ConcurrentHashMap<String, PaymentGatewayResult> results = new ConcurrentHashMap<>();

    @Override
    public Optional<PaymentGatewayResult> find(String paymentId) {
        return Optional.ofNullable(results.get(paymentId));
    }

    @Override
    public void save(String paymentId, PaymentGatewayResult result) {
        results.putIfAbsent(paymentId, result);
    }
}
