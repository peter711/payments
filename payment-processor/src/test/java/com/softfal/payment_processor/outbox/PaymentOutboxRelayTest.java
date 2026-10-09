package com.softfal.payment_processor.outbox;

import com.softfal.payment_processor.metrics.PaymentProcessorMetrics;
import com.softfal.payments.contracts.event.EventEnvelope;
import com.softfal.payments.contracts.event.EventEnvelopes;
import com.softfal.payments.contracts.event.EventType;
import com.softfal.payments.contracts.event.PaymentCompleted;
import com.softfal.payments.contracts.topic.PaymentTopics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentOutboxRelayTest {

    private static final int MAX_ATTEMPTS = 3;

    private final OutboxStore outboxStore = mock(OutboxStore.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OutboxProperties properties =
            new OutboxProperties(100, MAX_ATTEMPTS, 1000L, 60_000L, 3_600_000L, Duration.ofHours(24));

    private final PaymentProcessorMetrics metrics =
            new PaymentProcessorMetrics(new SimpleMeterRegistry(), outboxStore);

    private final PaymentOutboxRelay relay =
            new PaymentOutboxRelay(outboxStore, kafkaTemplate, objectMapper, properties, metrics);

    @Test
    void publishesAndMarksEvent() {
        OutboxEvent event = completedEvent(1L, "pay-1", "acc-1", 0);
        when(outboxStore.findPublishable(100)).thenReturn(List.of(event));
        stubSendSuccess();

        relay.publishPending();

        verify(kafkaTemplate).send(eq(PaymentTopics.COMPLETED), eq("acc-1"), any());
        verify(outboxStore).markPublished(1L);
    }

    @Test
    void recordsFailedAttemptWithBackoffWhenSendFails() {
        OutboxEvent event = completedEvent(1L, "pay-1", "acc-1", 0);
        when(outboxStore.findPublishable(100)).thenReturn(List.of(event));
        doThrow(new RuntimeException("simulated broker outage"))
                .when(kafkaTemplate).send(anyString(), any(), any());

        relay.publishPending();

        verify(outboxStore, never()).markPublished(1L);
        verify(outboxStore).markFailed(eq(1L), anyString(), any(), eq(MAX_ATTEMPTS));
    }

    @Test
    void doesNothingWhenThereAreNoEvents() {
        when(outboxStore.findPublishable(100)).thenReturn(List.of());

        relay.publishPending();

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void purgesPublishedEventsOlderThanRetention() {
        relay.purgePublishedEvents();

        verify(outboxStore).deletePublishedBefore(any());
    }

    private void stubSendSuccess() {
        @SuppressWarnings("unchecked")
        SendResult<String, Object> sendResult = mock(SendResult.class);
        doReturn(CompletableFuture.completedFuture(sendResult))
                .when(kafkaTemplate).send(anyString(), any(), any());
    }

    private OutboxEvent completedEvent(long id, String paymentId, String accountId, int attempts) {
        PaymentCompleted completed = new PaymentCompleted(
                paymentId, accountId, new BigDecimal("10.00"), "PLN", Instant.parse("2026-01-01T00:00:00Z"));
        EventEnvelope envelope =
                EventEnvelopes.wrap(EventType.PAYMENT_COMPLETED, completed, objectMapper, "corr-1", null);
        return new OutboxEvent(
                id, paymentId, PaymentTopics.COMPLETED, accountId,
                objectMapper.writeValueAsString(envelope), attempts, Instant.now());
    }
}
