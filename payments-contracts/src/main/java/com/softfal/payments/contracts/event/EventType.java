package com.softfal.payments.contracts.event;

import com.softfal.payments.contracts.topic.PaymentTopics;

import java.util.Arrays;
import java.util.Optional;

/**
 * Registry binding each {@link PaymentEvent} to its Kafka topic, logical event
 * name, payload schema version and JSON schema resource.
 *
 * <p>It removes the need for hard-coded {@code topic -> class} switches: the
 * outbox recorder resolves the topic and event name here, consumers resolve the
 * payload class from the topic, and the mapping is the single place to update
 * when a new event or a new schema version is introduced.</p>
 */
public enum EventType {

    PAYMENT_REQUESTED(PaymentTopics.REQUESTED, "payment.requested", 1,
            PaymentRequested.class, "payment.requested.v1.json"),
    PAYMENT_COMPLETED(PaymentTopics.COMPLETED, "payment.completed", 1,
            PaymentCompleted.class, "payment.completed.v1.json"),
    PAYMENT_FAILED(PaymentTopics.FAILED, "payment.failed", 1,
            PaymentFailed.class, "payment.failed.v1.json"),
    PAYMENT_PROVIDER_EVENT(PaymentTopics.PROVIDER_EVENTS, "payment.provider_event", 1,
            PaymentProviderEvent.class, "payment.provider_event.v1.json"),
    PAYMENT_CORRECTED(PaymentTopics.CORRECTED, "payment.corrected", 1,
            PaymentCorrected.class, "payment.corrected.v1.json");

    private final String topic;
    private final String eventType;
    private final int version;
    private final Class<? extends PaymentEvent> eventClass;
    private final String payloadSchemaResource;

    EventType(String topic,
              String eventType,
              int version,
              Class<? extends PaymentEvent> eventClass,
              String payloadSchemaResource) {
        this.topic = topic;
        this.eventType = eventType;
        this.version = version;
        this.eventClass = eventClass;
        this.payloadSchemaResource = payloadSchemaResource;
    }

    public String topic() {
        return topic;
    }

    /** Logical name placed in {@link EventEnvelope#eventType()}. */
    public String eventType() {
        return eventType;
    }

    /** Current schema version of the payload. */
    public int version() {
        return version;
    }

    public Class<? extends PaymentEvent> eventClass() {
        return eventClass;
    }

    /** Name of the JSON schema resource (under {@code /schema}) describing the payload. */
    public String payloadSchemaResource() {
        return payloadSchemaResource;
    }

    /**
     * Resolves the event type travelling on the given topic.
     *
     * @throws IllegalArgumentException when the topic is not a registered event topic
     */
    public static EventType fromTopic(String topic) {
        return Arrays.stream(values())
                .filter(type -> type.topic.equals(topic))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment topic: " + topic));
    }

    /**
     * Resolves the event type from the logical name carried in the envelope.
     *
     * @throws IllegalArgumentException when the name is not a registered event type
     */
    public static EventType fromType(String eventType) {
        return Arrays.stream(values())
                .filter(type -> type.eventType.equals(eventType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment event type: " + eventType));
    }

    public static Optional<EventType> findFromType(String eventType) {
        return Arrays.stream(values())
                .filter(type -> type.eventType.equals(eventType))
                .findFirst();
    }
}
