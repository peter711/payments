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

    /** Provider-reported status updates published by the webhook edge. */
    public static final String PROVIDER_EVENTS = "payments.provider.events";

    /** Corrections of a recorded failure, published by {@code payment-processor}. */
    public static final String CORRECTED = "payments.corrected";

    /** Dead letter topic for records that cannot be processed. */
    public static final String REQUESTED_DEAD_LETTER = "payments.requested.DLT";

    /** Dead letter topic for provider-event records that cannot be processed. */
    public static final String PROVIDER_EVENTS_DEAD_LETTER = "payments.provider.events.DLT";

    private PaymentTopics() {
    }
}
