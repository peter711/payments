package com.softfal.payment_processor.outbox;

import com.softfal.payment_processor.idempotency.InMemoryProcessedPaymentStore;
import com.softfal.payment_processor.model.PaymentOutcome;
import com.softfal.payments.contracts.event.EventEnvelope;
import com.softfal.payments.contracts.event.PaymentCorrected;
import com.softfal.payments.contracts.topic.PaymentTopics;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPaymentOutcomeRecorderTest {

    private final InMemoryProcessedPaymentStore store = new InMemoryProcessedPaymentStore();
    private final InMemoryOutboxStore outboxStore = new InMemoryOutboxStore();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutboxPaymentOutcomeRecorder recorder =
            new OutboxPaymentOutcomeRecorder(store, outboxStore, objectMapper);

    @Test
    void correctionIsEnqueuedOnceAndIsIdempotentOnReplay() throws Exception {
        store.claim("pay-1");
        recorder.record(outcome("pay-1", false), "corr-1", "evt-requested");

        boolean corrected = recorder.reconcile(outcome("pay-1", true), "corr-1", "evt-provider");
        assertThat(corrected).isTrue();
        assertThat(topics()).containsExactly(PaymentTopics.FAILED, PaymentTopics.CORRECTED);

        boolean again = recorder.reconcile(outcome("pay-1", true), "corr-1", "evt-provider");
        assertThat(again).isFalse();
        assertThat(topics()).containsExactly(PaymentTopics.FAILED, PaymentTopics.CORRECTED);

        EventEnvelope correctedEnvelope = objectMapper.readValue(
                outboxStore.events().get(1).payload(), EventEnvelope.class);
        PaymentCorrected payload = objectMapper.treeToValue(correctedEnvelope.payload(), PaymentCorrected.class);
        assertThat(payload.previousStatus()).isEqualTo("FAILED");
        assertThat(payload.newStatus()).isEqualTo("COMPLETED");
        assertThat(correctedEnvelope.causationId()).isEqualTo("evt-provider");
    }

    @Test
    void finalizesAPaymentWithNoPriorOutcome() {
        boolean corrected = recorder.reconcile(outcome("pay-2", true), "pay-2", "evt-provider");

        assertThat(corrected).isFalse();
        assertThat(topics()).containsExactly(PaymentTopics.COMPLETED);
    }

    @Test
    void providerFailureForAnAlreadyCompletedPaymentIsNoOp() {
        store.claim("pay-3");
        recorder.record(outcome("pay-3", true), "corr-3", "evt-requested");

        boolean corrected = recorder.reconcile(outcome("pay-3", false), "corr-3", "evt-provider");

        assertThat(corrected).isFalse();
        assertThat(topics()).containsExactly(PaymentTopics.COMPLETED);
    }

    private List<String> topics() {
        return outboxStore.events().stream().map(OutboxEvent::topic).toList();
    }

    private static PaymentOutcome outcome(String paymentId, boolean successful) {
        return new PaymentOutcome(paymentId, "acc-1", new BigDecimal("12.34"), "PLN", successful,
                successful ? null : "declined", Instant.parse("2026-01-01T00:00:00Z"));
    }
}
