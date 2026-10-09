package com.softfal.payment_processor.listener;

import com.softfal.payment_processor.exception.InvalidPaymentException;
import com.softfal.payment_processor.gateway.PaymentGateway;
import com.softfal.payment_processor.gateway.PaymentGatewayResult;
import com.softfal.payment_processor.idempotency.ProcessedPaymentStore;
import com.softfal.payment_processor.metrics.PaymentProcessorMetrics;
import com.softfal.payment_processor.model.PaymentOutcome;
import com.softfal.payment_processor.outbox.PaymentOutcomeRecorder;
import com.softfal.payments.contracts.event.EventEnvelope;
import com.softfal.payments.contracts.event.EventEnvelopes;
import com.softfal.payments.contracts.event.PaymentRequested;
import com.softfal.payments.contracts.topic.PaymentTopics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

/**
 * Consumes payment events from {@code payments.requested}, delegates the actual
 * gateway call to a {@link PaymentGateway}, and records the outcome.
 *
 * <p>Processing is idempotent per {@code paymentId}. Before touching the gateway
 * the listener checks a durable {@link ProcessedPaymentStore}; a redelivered
 * record whose outcome is already known is skipped, so the gateway is never
 * called twice. The outcome and its outgoing event are written atomically by the
 * {@link PaymentOutcomeRecorder} (transactional outbox); publishing is handled
 * separately by the outbox relay.</p>
 *
 * <p>The incoming {@link EventEnvelope} carries the tracing context: its
 * {@code correlationId} is propagated to the outgoing event and its
 * {@code eventId} becomes the outgoing {@code causationId}.</p>
 */
@Component
public class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);

    private final PaymentGateway paymentGateway;
    private final ProcessedPaymentStore processedPaymentStore;
    private final PaymentOutcomeRecorder outcomeRecorder;
    private final PaymentProcessorMetrics metrics;
    private final ObjectMapper objectMapper;

    public PaymentProcessor(PaymentGateway paymentGateway,
                            ProcessedPaymentStore processedPaymentStore,
                            PaymentOutcomeRecorder outcomeRecorder,
                            PaymentProcessorMetrics metrics,
                            ObjectMapper objectMapper) {
        this.paymentGateway = paymentGateway;
        this.processedPaymentStore = processedPaymentStore;
        this.outcomeRecorder = outcomeRecorder;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = PaymentTopics.REQUESTED)
    public void onPaymentRequested(EventEnvelope envelope) {
        PaymentRequested event = payload(envelope);
        log.info("Processing payment paymentId={} for accountId={} eventId={} correlationId={}",
                event.paymentId(), event.accountId(), envelope.eventId(), envelope.correlationId());

        validate(event);

        Optional<PaymentOutcome> previousOutcome = processedPaymentStore.findFinal(event.paymentId());
        if (previousOutcome.isPresent()) {
            log.warn("Duplicate paymentId={} detected; outcome already recorded and queued in the outbox",
                    event.paymentId());
            metrics.duplicateSkipped();
            return;
        }

        processedPaymentStore.claim(event.paymentId());

        Timer.Sample sample = metrics.startGatewayCall();
        PaymentGatewayResult result;
        try {
            result = paymentGateway.callPaymentGateway(event);
        } finally {
            metrics.stopGatewayCall(sample);
        }
        PaymentOutcome outcome = new PaymentOutcome(
                event.paymentId(),
                event.accountId(),
                event.amount(),
                event.currency(),
                result.successful(),
                result.reason(),
                Instant.now());
        metrics.outcome(result.successful());
        outcomeRecorder.record(outcome, envelope.correlationId(), envelope.eventId());
    }

    private PaymentRequested payload(EventEnvelope envelope) {
        try {
            return EventEnvelopes.payload(envelope, PaymentRequested.class, objectMapper);
        } catch (RuntimeException ex) {
            // A syntactically valid envelope whose payload does not match the
            // contract is a permanent failure; dead-letter it without retrying.
            throw new InvalidPaymentException("Invalid payment.requested payload: " + ex.getMessage());
        }
    }

    /**
     * Validates the incoming event before it reaches the gateway. Any failure is
     * permanent, so {@link InvalidPaymentException} is configured as a
     * non-retryable exception and the record is routed straight to the dead
     * letter topic.
     */
    private void validate(PaymentRequested event) {
        if (isBlank(event.paymentId())) {
            throw new InvalidPaymentException("paymentId must not be blank");
        }
        if (isBlank(event.accountId())) {
            throw new InvalidPaymentException("accountId must not be blank");
        }
        if (isBlank(event.currency())) {
            throw new InvalidPaymentException("currency must not be blank");
        }
        if (event.amount() == null || event.amount().signum() <= 0) {
            throw new InvalidPaymentException(
                    "amount must be greater than zero, was " + event.amount());
        }
        log.debug("Payment validation passed for paymentId={}", event.paymentId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
