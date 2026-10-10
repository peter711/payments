package com.softfal.notification_service.listener;

import com.softfal.notification_service.gateway.NotificationSender;
import com.softfal.notification_service.idempotency.NotificationDedupStore;
import com.softfal.notification_service.metrics.NotificationMetrics;
import com.softfal.payments.contracts.event.EventEnvelope;
import com.softfal.payments.contracts.event.EventEnvelopes;
import com.softfal.payments.contracts.event.EventType;
import com.softfal.payments.contracts.event.PaymentEvent;
import com.softfal.payments.contracts.topic.PaymentTopics;
import com.softfal.payments.observability.LogContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes payment outcomes from {@code payments.completed} and
 * {@code payments.failed}, unwraps the typed event from the envelopes and
 * forwards it to a {@link NotificationSender}.
 *
 * <p>Because the processor may legitimately replay an outcome after a crash, the
 * listener deduplicates by {@code (paymentId, topic)} using a durable
 * {@link NotificationDedupStore} so the same payment is only notified once.</p>
 */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationSender notificationSender;
    private final NotificationDedupStore dedupStore;
    private final NotificationMetrics metrics;
    private final ObjectMapper objectMapper;

    public NotificationListener(NotificationSender notificationSender,
                                NotificationDedupStore dedupStore,
                                NotificationMetrics metrics,
                                ObjectMapper objectMapper) {
        this.notificationSender = notificationSender;
        this.dedupStore = dedupStore;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = PaymentTopics.COMPLETED)
    public void onPaymentCompleted(ConsumerRecord<String, EventEnvelope> record) {
        handle(record.topic(), record.value());
    }

    @KafkaListener(topics = PaymentTopics.FAILED)
    public void onPaymentFailed(ConsumerRecord<String, EventEnvelope> record) {
        handle(record.topic(), record.value());
    }

    @KafkaListener(topics = PaymentTopics.CORRECTED)
    public void onPaymentCorrected(ConsumerRecord<String, EventEnvelope> record) {
        handle(record.topic(), record.value());
    }

    private void handle(String topic, EventEnvelope envelope) {
        try (var logContext = LogContext.scope()
                .topic(topic)
                .correlationId(envelope.correlationId())
                .causationId(envelope.causationId())
                .eventId(envelope.eventId())
                .eventType(envelope.eventType())) {
            log.debug("Received {} event: {}", topic, envelope);

            PaymentEvent event = unwrap(topic, envelope);
            if (event == null) {
                return;
            }

            String paymentId = event.paymentId();
            logContext.paymentId(paymentId);
            if (paymentId != null && !paymentId.isBlank()
                    && !dedupStore.markProcessed(envelope.eventId(), paymentId, topic, rankOf(topic))) {
                log.warn("Duplicate or lower-ranked outcome for paymentId={} on topic '{}'; skipping notification",
                        paymentId, topic);
                metrics.duplicateSkipped();
                return;
            }

            try {
                notificationSender.send(topic, event);
                metrics.notificationSent();
                if (PaymentTopics.CORRECTED.equals(topic)) {
                    metrics.correctionSent();
                }
            } catch (RuntimeException ex) {
                metrics.notificationFailed();
                throw ex;
            }
        }
    }

    /**
     * Monotonic rank of an outcome: a success (including a correction) outranks a
     * failure, so a lower-ranked outcome delivered after a higher one is skipped.
     */
    private static int rankOf(String topic) {
        return PaymentTopics.FAILED.equals(topic) ? 1 : 2;
    }

    /**
     * The topic determines the payload type, so a corrupt payload is rejected
     * here rather than being forwarded unchecked.
     */
    private PaymentEvent unwrap(String topic, EventEnvelope envelope) {
        try {
            EventType type = EventType.fromTopic(topic);
            return EventEnvelopes.payload(envelope, type.eventClass(), objectMapper);
        } catch (RuntimeException ex) {
            log.warn("Could not read event from topic '{}' (eventId={}); skipping notification",
                    topic, envelope.eventId(), ex);
            return null;
        }
    }
}
