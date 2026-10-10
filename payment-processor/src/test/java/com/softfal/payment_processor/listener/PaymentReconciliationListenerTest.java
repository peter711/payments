package com.softfal.payment_processor.listener;

import com.softfal.payment_processor.exception.InvalidProviderEventException;
import com.softfal.payment_processor.idempotency.InMemoryProcessedPaymentStore;
import com.softfal.payment_processor.metrics.PaymentProcessorMetrics;
import com.softfal.payment_processor.model.PaymentOutcome;
import com.softfal.payment_processor.outbox.InMemoryOutboxStore;
import com.softfal.payment_processor.outbox.OutboxEvent;
import com.softfal.payment_processor.outbox.OutboxPaymentOutcomeRecorder;
import com.softfal.payments.contracts.event.EventEnvelope;
import com.softfal.payments.contracts.event.EventEnvelopes;
import com.softfal.payments.contracts.event.EventType;
import com.softfal.payments.contracts.event.PaymentProviderEvent;
import com.softfal.payments.contracts.event.ProviderPaymentStatus;
import com.softfal.payments.contracts.topic.PaymentTopics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentReconciliationListenerTest {

    private final InMemoryProcessedPaymentStore store = new InMemoryProcessedPaymentStore();
    private final InMemoryOutboxStore outboxStore = new InMemoryOutboxStore();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PaymentProcessorMetrics metrics = new PaymentProcessorMetrics(registry, outboxStore);

    private PaymentReconciliationListener listener;

    @BeforeEach
    void setUp() {
        OutboxPaymentOutcomeRecorder recorder = new OutboxPaymentOutcomeRecorder(store, outboxStore, objectMapper);
        listener = new PaymentReconciliationListener(recorder, metrics, objectMapper);
    }

    @Test
    void finalizesAPaymentStillInProgress() {
        store.claim("pay-1");

        listener.onProviderEvent(envelope("pay-1", ProviderPaymentStatus.SUCCEEDED));

        assertThat(topics()).containsExactly(PaymentTopics.COMPLETED);
    }

    @Test
    void correctsARecordedFailureAndCountsTheCorrection() {
        store.claim("pay-1");
        store.complete(new PaymentOutcome("pay-1", "acc-1", new BigDecimal("12.34"), "PLN", false,
                "requires_action", Instant.parse("2026-01-01T00:00:00Z")));

        listener.onProviderEvent(envelope("pay-1", ProviderPaymentStatus.SUCCEEDED));

        assertThat(topics()).containsExactly(PaymentTopics.CORRECTED);
        assertThat(registry.get("payments.processor.corrections").counter().count()).isEqualTo(1.0);
    }

    @Test
    void leavesAnAlreadyCompletedPaymentAlone() {
        store.claim("pay-1");
        store.complete(new PaymentOutcome("pay-1", "acc-1", new BigDecimal("12.34"), "PLN", true,
                null, Instant.parse("2026-01-01T00:00:00Z")));

        listener.onProviderEvent(envelope("pay-1", ProviderPaymentStatus.FAILED));

        assertThat(outboxStore.events()).isEmpty();
    }

    @Test
    void rejectsAMalformedProviderEvent() {
        EventEnvelope malformed = new EventEnvelope("evt-bad", EventType.PAYMENT_PROVIDER_EVENT.eventType(),
                EventType.PAYMENT_PROVIDER_EVENT.version(), Instant.now(), "corr-1", null,
                objectMapper.valueToTree(Map.of("paymentId", "pay-1", "providerStatus", "bogus")));

        assertThatThrownBy(() -> listener.onProviderEvent(malformed))
                .isInstanceOf(InvalidProviderEventException.class);
    }

    private EventEnvelope envelope(String paymentId, ProviderPaymentStatus status) {
        PaymentProviderEvent event = new PaymentProviderEvent(paymentId, "acc-1", new BigDecimal("12.34"), "PLN",
                status, "evt-1", status == ProviderPaymentStatus.FAILED ? "declined" : null,
                Instant.parse("2026-01-01T00:00:00Z"));
        return EventEnvelopes.wrap(EventType.PAYMENT_PROVIDER_EVENT, event, objectMapper, paymentId, null);
    }

    private List<String> topics() {
        return outboxStore.events().stream().map(OutboxEvent::topic).toList();
    }
}
