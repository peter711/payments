package com.softfal.payment_processor.outbox;

import com.softfal.payment_processor.idempotency.ProcessedPaymentStore;
import com.softfal.payment_processor.model.PaymentOutcome;
import com.softfal.payments.contracts.event.EventEnvelope;
import com.softfal.payments.contracts.event.EventEnvelopes;
import com.softfal.payments.contracts.event.EventType;
import com.softfal.payments.contracts.event.PaymentCompleted;
import com.softfal.payments.contracts.event.PaymentCorrected;
import com.softfal.payments.contracts.event.PaymentEvent;
import com.softfal.payments.contracts.event.PaymentFailed;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Writes the finalized outcome and its outgoing event in a single database
 * transaction. If the commit succeeds, the event is guaranteed to be in the
 * outbox; if anything fails, neither the outcome nor the event is stored and the
 * input record can be retried safely.
 */
@Component
public class OutboxPaymentOutcomeRecorder implements PaymentOutcomeRecorder {

    private final ProcessedPaymentStore processedPaymentStore;
    private final OutboxStore outboxStore;
    private final ObjectMapper objectMapper;

    public OutboxPaymentOutcomeRecorder(ProcessedPaymentStore processedPaymentStore,
                                        OutboxStore outboxStore,
                                        ObjectMapper objectMapper) {
        this.processedPaymentStore = processedPaymentStore;
        this.outboxStore = outboxStore;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void record(PaymentOutcome outcome, String correlationId, String causationId) {
        if (!processedPaymentStore.complete(outcome)) {
            // The payment already reached an equal or higher state (for example a
            // webhook finalized it first); do not emit a second outcome event.
            return;
        }
        if (outcome.successful()) {
            enqueue(EventType.PAYMENT_COMPLETED, completed(outcome), outcome, correlationId, causationId);
        } else {
            enqueue(EventType.PAYMENT_FAILED, failed(outcome), outcome, correlationId, causationId);
        }
    }

    @Override
    @Transactional
    public boolean reconcile(PaymentOutcome outcome, String correlationId, String causationId) {
        // Ensure a row exists so a payment recovered purely from the provider
        // (never claimed locally) can still be finalized.
        processedPaymentStore.claim(outcome.paymentId());
        String previousStatus = processedPaymentStore.findFinal(outcome.paymentId())
                .map(OutboxPaymentOutcomeRecorder::status)
                .orElse("IN_PROGRESS");

        if (!processedPaymentStore.complete(outcome)) {
            return false;
        }

        if ("FAILED".equals(previousStatus) && outcome.successful()) {
            enqueue(EventType.PAYMENT_CORRECTED, corrected(outcome, previousStatus), outcome, correlationId, causationId);
            return true;
        }
        if (outcome.successful()) {
            enqueue(EventType.PAYMENT_COMPLETED, completed(outcome), outcome, correlationId, causationId);
        } else {
            enqueue(EventType.PAYMENT_FAILED, failed(outcome), outcome, correlationId, causationId);
        }
        return false;
    }

    private void enqueue(EventType type,
                         PaymentEvent event,
                         PaymentOutcome outcome,
                         String correlationId,
                         String causationId) {
        EventEnvelope envelope = EventEnvelopes.wrap(type, event, objectMapper, correlationId, causationId);
        outboxStore.enqueue(
                outcome.paymentId(),
                type.topic(),
                outcome.accountId(),
                objectMapper.writeValueAsString(envelope));
    }

    private static PaymentCompleted completed(PaymentOutcome outcome) {
        return new PaymentCompleted(
                outcome.paymentId(),
                outcome.accountId(),
                outcome.amount(),
                outcome.currency(),
                outcome.completedAt());
    }

    private static PaymentCorrected corrected(PaymentOutcome outcome, String previousStatus) {
        return new PaymentCorrected(
                outcome.paymentId(),
                outcome.accountId(),
                outcome.amount(),
                outcome.currency(),
                previousStatus,
                status(outcome),
                outcome.completedAt());
    }

    private static String status(PaymentOutcome outcome) {
        return outcome.successful() ? "COMPLETED" : "FAILED";
    }

    private static PaymentFailed failed(PaymentOutcome outcome) {
        return new PaymentFailed(
                outcome.paymentId(),
                outcome.accountId(),
                outcome.amount(),
                outcome.currency(),
                outcome.reason(),
                outcome.completedAt());
    }
}
