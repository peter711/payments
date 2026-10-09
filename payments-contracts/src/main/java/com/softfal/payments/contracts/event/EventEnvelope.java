package com.softfal.payments.contracts.event;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Transport-level wrapper around every {@link PaymentEvent}.
 *
 * <p>The envelope makes each Kafka record self-describing: consumers can read
 * the event identity, version and tracing context without knowing the domain
 * type up front. The domain event itself is carried as a {@link JsonNode} so the
 * envelope stays non-generic and can be deserialized by a plain Jackson
 * deserializer; callers convert the payload with
 * {@link EventEnvelopes#payload(EventEnvelope, Class, tools.jackson.databind.ObjectMapper)}.</p>
 *
 * @param eventId       unique id of this event; the deduplication key
 * @param eventType     logical event name, e.g. {@code payment.completed}
 * @param eventVersion  schema version of the payload
 * @param occurredAt    domain timestamp copied from the payload event
 * @param correlationId id tying together all events of one business flow
 * @param causationId   id of the event that caused this one, {@code null} for the root
 * @param payload       the serialized domain event
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String correlationId,
        String causationId,
        JsonNode payload
) {
}
