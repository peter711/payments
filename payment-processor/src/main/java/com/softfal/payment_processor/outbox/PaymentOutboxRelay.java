package com.softfal.payment_processor.outbox;

import com.softfal.payment_processor.metrics.PaymentProcessorMetrics;
import com.softfal.payments.contracts.event.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polling publisher for the transactional outbox.
 *
 * <p>Periodically reads publishable events and sends them to Kafka, marking each
 * one as published only after the broker acknowledged it. A failed send bumps the
 * attempt counter and schedules an exponential retry; after {@code max-attempts}
 * the event is marked {@code FAILED} and stops being retried, which closes the
 * gap where an outcome was committed but its event never reached Kafka.</p>
 *
 * <p>Delivery is at-least-once: a crash between publishing and marking may
 * republish an event, which downstream consumers absorb idempotently.</p>
 */
@Component
public class PaymentOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutboxRelay.class);

    private static final long PUBLISH_TIMEOUT_SECONDS = 10L;
    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxStore outboxStore;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;
    private final PaymentProcessorMetrics metrics;

    public PaymentOutboxRelay(OutboxStore outboxStore,
                              KafkaTemplate<String, Object> kafkaTemplate,
                              ObjectMapper objectMapper,
                              OutboxProperties properties,
                              PaymentProcessorMetrics metrics) {
        this.outboxStore = outboxStore;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${payment.outbox.poll-interval-ms:500}",
            initialDelayString = "${payment.outbox.initial-delay-ms:500}")
    public void publishPending() {
        List<OutboxEvent> events = outboxStore.findPublishable(properties.batchSize());
        if (events.isEmpty()) {
            return;
        }
        log.debug("Publishing {} pending outbox event(s)", events.size());
        for (OutboxEvent event : events) {
            try {
                publish(event);
                outboxStore.markPublished(event.id());
                metrics.outboxPublished();
            } catch (Exception ex) {
                handleFailure(event, ex);
            }
        }
    }

    @Scheduled(fixedDelayString = "${payment.outbox.cleanup-interval-ms:3600000}",
            initialDelayString = "${payment.outbox.cleanup-interval-ms:3600000}")
    public void purgePublishedEvents() {
        int deleted = outboxStore.deletePublishedBefore(Instant.now().minus(properties.retention()));
        if (deleted > 0) {
            log.info("Purged {} published outbox event(s) older than {}", deleted, properties.retention());
        }
    }

    private void handleFailure(OutboxEvent event, Exception ex) {
        metrics.outboxPublishFailed();
        int attempts = event.attempts() + 1;
        boolean exhausted = attempts >= properties.maxAttempts();
        long backoff = properties.backoffMillis(attempts);
        outboxStore.markFailed(
                event.id(),
                describe(ex),
                Instant.now().plusMillis(backoff),
                properties.maxAttempts());

        if (exhausted) {
            log.error("Outbox event id={} paymentId={} exhausted {} publish attempt(s); marking FAILED",
                    event.id(), event.paymentId(), attempts, ex);
        } else {
            log.warn("Failed to publish outbox event id={} paymentId={} (attempt {}); retrying in {} ms",
                    event.id(), event.paymentId(), attempts, backoff, ex);
        }
    }

    private void publish(OutboxEvent event) throws Exception {
        Object payload = deserialize(event);
        kafkaTemplate.send(event.topic(), event.messageKey(), payload)
                .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        log.info("Published outbox event id={} paymentId={} to topic '{}'",
                event.id(), event.paymentId(), event.topic());
    }

    private Object deserialize(OutboxEvent event) throws Exception {
        // The stored payload is a complete envelope; deserializing it validates
        // the metadata before the record is sent to the broker.
        return objectMapper.readValue(event.payload(), EventEnvelope.class);
    }

    private static String describe(Exception ex) {
        String message = ex.getClass().getSimpleName()
                + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
