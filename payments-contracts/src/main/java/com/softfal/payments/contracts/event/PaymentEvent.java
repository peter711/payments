package com.softfal.payments.contracts.event;

import java.time.Instant;

/**
 * Common contract shared by every payment-related event. All of them are keyed
 * by the payment id, which doubles as the idempotency key and the Kafka message
 * key used to keep a payment's events ordered.
 */
public interface PaymentEvent {

    String paymentId();

    /**
     * Domain timestamp of the event. It is copied into
     * {@link EventEnvelope#occurredAt()} when the event is wrapped, so consumers
     * can read the moment of the event without parsing the payload.
     */
    Instant occurredAt();
}
