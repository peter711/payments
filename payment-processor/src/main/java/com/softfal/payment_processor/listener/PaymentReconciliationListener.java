package com.softfal.payment_processor.listener;

import com.softfal.payment_processor.exception.InvalidProviderEventException;
import com.softfal.payment_processor.metrics.PaymentProcessorMetrics;
import com.softfal.payment_processor.model.PaymentOutcome;
import com.softfal.payment_processor.outbox.PaymentOutcomeRecorder;
import com.softfal.payments.contracts.event.EventEnvelope;
import com.softfal.payments.contracts.event.EventEnvelopes;
import com.softfal.payments.contracts.event.PaymentProviderEvent;
import com.softfal.payments.contracts.event.ProviderPaymentStatus;
import com.softfal.payments.contracts.topic.PaymentTopics;
import com.softfal.payments.observability.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Consumes provider-reported status updates from {@code payments.provider.events}
 * and reconciles them with the durable payment state.
 *
 * <p>It never calls the gateway: it only applies the provider's reported outcome.
 * A payment still {@code IN_PROGRESS} is finalized; a recorded failure that the
 * provider now reports as succeeded is corrected (a {@code payment.corrected}
 * event is emitted); an already-completed payment is left alone. The monotonic
 * store makes redelivery idempotent.</p>
 */
@Component
public class PaymentReconciliationListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationListener.class);

    private final PaymentOutcomeRecorder outcomeRecorder;
    private final PaymentProcessorMetrics metrics;
    private final ObjectMapper objectMapper;

    public PaymentReconciliationListener(PaymentOutcomeRecorder outcomeRecorder,
                                         PaymentProcessorMetrics metrics,
                                         ObjectMapper objectMapper) {
        this.outcomeRecorder = outcomeRecorder;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = PaymentTopics.PROVIDER_EVENTS)
    public void onProviderEvent(EventEnvelope envelope) {
        PaymentProviderEvent event = payload(envelope);
        try (var logContext = LogContext.scope()
                .correlationId(envelope.correlationId())
                .causationId(envelope.causationId())
                .eventId(envelope.eventId())
                .eventType(envelope.eventType())
                .paymentId(event.paymentId())
                .accountId(event.accountId())
                .topic(PaymentTopics.PROVIDER_EVENTS)) {
            log.info("Reconciling paymentId={} providerStatus={} providerEventId={}",
                    event.paymentId(), event.providerStatus(), event.providerEventId());

            boolean successful = event.providerStatus() == ProviderPaymentStatus.SUCCEEDED;
            PaymentOutcome outcome = new PaymentOutcome(
                    event.paymentId(),
                    event.accountId(),
                    event.amount(),
                    event.currency(),
                    successful,
                    successful ? null : event.reason(),
                    Instant.now());

            if (outcomeRecorder.reconcile(outcome, envelope.correlationId(), envelope.eventId())) {
                metrics.correctionApplied();
            }
        }
    }

    private PaymentProviderEvent payload(EventEnvelope envelope) {
        PaymentProviderEvent event;
        try {
            event = EventEnvelopes.payload(envelope, PaymentProviderEvent.class, objectMapper);
        } catch (RuntimeException ex) {
            throw new InvalidProviderEventException("Invalid payment.provider_event payload: " + ex.getMessage());
        }
        if (event.paymentId() == null || event.paymentId().isBlank()) {
            throw new InvalidProviderEventException("payment.provider_event must carry a paymentId");
        }
        return event;
    }
}
