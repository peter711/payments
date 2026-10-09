package com.softfal.payments.contracts.event;

import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Helpers for producing and consuming {@link EventEnvelope}s with a shared
 * Jackson {@link ObjectMapper}. Keeping this logic in the contracts module means
 * the producer and every consumer agree on how an event is wrapped and unwrapped.
 */
public final class EventEnvelopes {

    private EventEnvelopes() {
    }

    /**
     * Wraps a domain event in a new envelope.
     *
     * @param type          the event type, source of the name, version and topic
     * @param event         the domain event; its {@code occurredAt} becomes the envelope timestamp
     * @param objectMapper  mapper used to turn the event into a JSON payload
     * @param correlationId id of the business flow this event belongs to
     * @param causationId   id of the event that caused this one, or {@code null}
     */
    public static EventEnvelope wrap(EventType type,
                                     PaymentEvent event,
                                     ObjectMapper objectMapper,
                                     String correlationId,
                                     String causationId) {
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                type.eventType(),
                type.version(),
                event.occurredAt(),
                correlationId,
                causationId,
                objectMapper.valueToTree(event));
    }

    /**
     * Converts the envelope payload back into its typed domain event.
     *
     * @throws tools.jackson.core.JacksonException when the payload does not match the type
     */
    public static <T extends PaymentEvent> T payload(EventEnvelope envelope,
                                                     Class<T> eventClass,
                                                     ObjectMapper objectMapper) {
        return objectMapper.treeToValue(envelope.payload(), eventClass);
    }
}
