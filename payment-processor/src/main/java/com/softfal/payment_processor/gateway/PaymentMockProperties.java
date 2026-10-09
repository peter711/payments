package com.softfal.payment_processor.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the local {@link MockPaymentGateway}.
 *
 * @param successRate probability (0..1) that the mock gateway accepts a payment
 * @param delayMs     simulated latency of the gateway call, in milliseconds
 */
@ConfigurationProperties(prefix = "payment.mock")
public record PaymentMockProperties(Double successRate, Long delayMs) {

    public PaymentMockProperties {
        if (successRate == null) {
            successRate = 0.8;
        }
        if (delayMs == null) {
            delayMs = 200L;
        }
        if (successRate < 0.0 || successRate > 1.0) {
            throw new IllegalArgumentException("payment.mock.success-rate must be between 0.0 and 1.0, was " + successRate);
        }
        if (delayMs < 0L) {
            throw new IllegalArgumentException("payment.mock.delay-ms must not be negative, was " + delayMs);
        }
    }
}
