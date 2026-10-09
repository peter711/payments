package com.softfal.payment_api.service;

import com.softfal.payment_api.metrics.PaymentApiMetrics;
import com.softfal.payments.contracts.event.EventEnvelope;
import com.softfal.payments.contracts.event.EventEnvelopes;
import com.softfal.payments.contracts.event.EventType;
import com.softfal.payments.contracts.event.PaymentRequested;
import com.softfal.payments.contracts.topic.PaymentTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends payment events to the {@code payments.requested} Kafka topic.
 *
 * <p>The domain event is wrapped in an {@link EventEnvelope} so consumers get a
 * self-describing record carrying the event id, version and correlation id.</p>
 */
@Service
public class PaymentEventService {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventService.class);

    private final KafkaTemplate<String, EventEnvelope> kafkaTemplate;
    private final PaymentApiMetrics metrics;
    private final ObjectMapper objectMapper;

    public PaymentEventService(KafkaTemplate<String, EventEnvelope> kafkaTemplate,
                               PaymentApiMetrics metrics,
                               ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /**
     * Publishes the event using {@code accountId} as the message key.
     *
     * @param event         the payment request to publish
     * @param correlationId id tying this flow together; copied into the envelope
     */
    public void send(PaymentRequested event, String correlationId) {
        EventEnvelope envelope =
                EventEnvelopes.wrap(EventType.PAYMENT_REQUESTED, event, objectMapper, correlationId, null);

        log.info("Publishing {} to topic '{}' for accountId={} correlationId={} eventId={}",
                EventType.PAYMENT_REQUESTED.eventType(), PaymentTopics.REQUESTED,
                event.accountId(), correlationId, envelope.eventId());

        metrics.requestAccepted();
        kafkaTemplate.send(PaymentTopics.REQUESTED, event.accountId(), envelope)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        metrics.publishFailed();
                        log.error("Failed to publish payment event for accountId={}", event.accountId(), ex);
                    } else {
                        log.debug(
                                "Published payment event for accountId={} to partition {} at offset {}",
                                event.accountId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
