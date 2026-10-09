package com.softfal.notification_service.kafka;

import com.softfal.payments.contracts.event.EventEnvelope;
import com.softfal.payments.contracts.event.EventEnvelopeMapper;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

/**
 * Kafka value deserializer that reads every record into an {@link EventEnvelope}
 * using the shared {@code BigDecimal}-aware mapper. It is wired into the
 * {@code ErrorHandlingDeserializer} through
 * {@code spring.deserializer.value.delegate.class}.
 */
public class EventEnvelopeDeserializer extends JacksonJsonDeserializer<EventEnvelope> {

    public EventEnvelopeDeserializer() {
        super(EventEnvelope.class, EventEnvelopeMapper.create(), false);
    }
}
