package com.softfal.payments.contracts.topic;

/**
 * Single source of truth for the Kafka topic names used across the payments
 * system. Producers, consumers and the outbox relay all reference these
 * constants instead of hard-coded string literals.
 */
public final class PaymentTopics {

    /** Payment requests published by {@code payment-api}. */
    public static final String REQUESTED = "payments.requested";

    /** Successful outcomes published by {@code payment-processor}. */
    public static final String COMPLETED = "payments.completed";

    /** Failed outcomes published by {@code payment-processor}. */
    public static final String FAILED = "payments.failed";

    /** Dead letter topic for records that cannot be processed. */
    public static final String REQUESTED_DEAD_LETTER = "payments.requested.DLT";

    private PaymentTopics() {
    }
}
