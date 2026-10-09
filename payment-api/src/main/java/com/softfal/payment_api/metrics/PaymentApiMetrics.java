package com.softfal.payment_api.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business meters for the payment API.
 *
 * <p>Only bounded, low-cardinality dimensions are used as tags; identifiers such
 * as {@code paymentId} or {@code accountId} are never attached to a meter.</p>
 */
@Component
public class PaymentApiMetrics {

    private final Counter acceptedRequests;
    private final Counter publishErrors;

    public PaymentApiMetrics(MeterRegistry registry) {
        this.acceptedRequests = Counter.builder("payments.api.requests")
                .description("Payment requests accepted by the API")
                .tag("result", "accepted")
                .register(registry);
        this.publishErrors = Counter.builder("payments.api.publish.errors")
                .description("Payment events that failed to publish to Kafka")
                .register(registry);
    }

    public void requestAccepted() {
        acceptedRequests.increment();
    }

    public void publishFailed() {
        publishErrors.increment();
    }
}
