package com.softfal.payment_processor.listener;

import com.softfal.payment_processor.exception.InvalidPaymentException;
import com.softfal.payment_processor.idempotency.InMemoryProcessedPaymentStore;
import com.softfal.payment_processor.metrics.PaymentProcessorMetrics;
import com.softfal.payment_processor.outbox.InMemoryOutboxStore;
import com.softfal.payment_processor.outbox.OutboxEvent;
import com.softfal.payment_processor.outbox.OutboxPaymentOutcomeRecorder;
import com.softfal.payment_processor.outbox.PaymentOutcomeRecorder;
import com.softfal.payments.contracts.event.EventEnvelope;
import com.softfal.payments.contracts.event.EventEnvelopes;
import com.softfal.payments.contracts.event.EventType;
import com.softfal.payments.contracts.event.PaymentRequested;
import com.softfal.payments.contracts.topic.PaymentTopics;
import com.softfal.payments.gateway.GatewayTimeoutException;
import com.softfal.payments.gateway.PaymentGateway;
import com.softfal.payments.gateway.PaymentGatewayResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentProcessorTest {

    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);

    private final InMemoryProcessedPaymentStore processedPaymentStore = new InMemoryProcessedPaymentStore();

    private final InMemoryOutboxStore outboxStore = new InMemoryOutboxStore();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final PaymentOutcomeRecorder outcomeRecorder =
            new OutboxPaymentOutcomeRecorder(processedPaymentStore, outboxStore, objectMapper);

    private final PaymentProcessorMetrics metrics =
            new PaymentProcessorMetrics(new SimpleMeterRegistry(), outboxStore);

    private final PaymentProcessor processor =
            new PaymentProcessor(paymentGateway, processedPaymentStore, outcomeRecorder, metrics, objectMapper);

    @Test
    void rejectsNonPositiveAmount() {
        EventEnvelope envelope = envelope(event("pay-1", "acc-1", new BigDecimal("0.00"), "PLN"));

        assertThatThrownBy(() -> processor.onPaymentRequested(envelope))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("amount");

        verifyNoInteractions(paymentGateway);
        assertThat(outboxStore.events()).isEmpty();
    }

    @Test
    void rejectsBlankPaymentId() {
        EventEnvelope envelope = envelope(event(" ", "acc-1", new BigDecimal("10.00"), "PLN"));

        assertThatThrownBy(() -> processor.onPaymentRequested(envelope))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("paymentId");

        verifyNoInteractions(paymentGateway);
    }

    @Test
    void rejectsBlankCurrency() {
        EventEnvelope envelope = envelope(event("pay-1", "acc-1", new BigDecimal("10.00"), null));

        assertThatThrownBy(() -> processor.onPaymentRequested(envelope))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("currency");

        verifyNoInteractions(paymentGateway);
    }

    @Test
    void propagatesGatewayTimeoutWithoutRecordingOutcome() {
        PaymentRequested event = event("pay-1", "acc-1", new BigDecimal("10.00"), "PLN");
        when(paymentGateway.callPaymentGateway(event))
                .thenThrow(new GatewayTimeoutException("simulated timeout"));

        assertThatThrownBy(() -> processor.onPaymentRequested(envelope(event)))
                .isInstanceOf(GatewayTimeoutException.class);

        assertThat(outboxStore.events()).isEmpty();
        assertThat(processedPaymentStore.findFinal("pay-1")).isEmpty();
    }

    @Test
    void recordsCompletedOutcomeAndOutboxEvent() {
        PaymentRequested event = event("pay-1", "acc-1", new BigDecimal("10.00"), "PLN");
        when(paymentGateway.callPaymentGateway(event)).thenReturn(PaymentGatewayResult.success());

        processor.onPaymentRequested(envelope(event));

        assertThat(processedPaymentStore.findFinal("pay-1")).isPresent();
        List<OutboxEvent> events = outboxStore.events();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).topic()).isEqualTo(PaymentTopics.COMPLETED);
        assertThat(events.get(0).paymentId()).isEqualTo("pay-1");
        assertThat(events.get(0).messageKey()).isEqualTo("acc-1");
    }

    @Test
    void recordsFailedOutcomeAndOutboxEvent() {
        PaymentRequested event = event("pay-2", "acc-1", new BigDecimal("10.00"), "PLN");
        when(paymentGateway.callPaymentGateway(event)).thenReturn(PaymentGatewayResult.failure("declined"));

        processor.onPaymentRequested(envelope(event));

        List<OutboxEvent> events = outboxStore.events();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).topic()).isEqualTo(PaymentTopics.FAILED);
    }

    @Test
    void propagatesCorrelationAndCausationToOutgoingEvent() throws Exception {
        PaymentRequested event = event("pay-1", "acc-1", new BigDecimal("10.00"), "PLN");
        EventEnvelope requested = envelope(event);
        when(paymentGateway.callPaymentGateway(event)).thenReturn(PaymentGatewayResult.success());

        processor.onPaymentRequested(requested);

        EventEnvelope outcome = objectMapper.readValue(outboxStore.events().get(0).payload(), EventEnvelope.class);
        assertThat(outcome.eventType()).isEqualTo(EventType.PAYMENT_COMPLETED.eventType());
        assertThat(outcome.correlationId()).isEqualTo("corr-1");
        assertThat(outcome.causationId()).isEqualTo(requested.eventId());
    }

    @Test
    void doesNotChargeGatewayTwiceOnRedelivery() {
        PaymentRequested event = event("pay-dup", "acc-1", new BigDecimal("10.00"), "PLN");
        EventEnvelope envelope = envelope(event);
        when(paymentGateway.callPaymentGateway(event)).thenReturn(PaymentGatewayResult.success());

        processor.onPaymentRequested(envelope);
        processor.onPaymentRequested(envelope);

        verify(paymentGateway, times(1)).callPaymentGateway(event);
        assertThat(outboxStore.events()).hasSize(1);
    }

    private EventEnvelope envelope(PaymentRequested event) {
        return EventEnvelopes.wrap(EventType.PAYMENT_REQUESTED, event, objectMapper, "corr-1", null);
    }

    private static PaymentRequested event(String paymentId, String accountId, BigDecimal amount, String currency) {
        return new PaymentRequested(paymentId, accountId, amount, currency, Instant.now());
    }
}
