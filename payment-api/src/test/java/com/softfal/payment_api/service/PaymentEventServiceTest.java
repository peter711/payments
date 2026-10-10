package com.softfal.payment_api.service;

import com.softfal.payment_api.metrics.PaymentApiMetrics;
import com.softfal.payments.contracts.event.EventEnvelope;
import com.softfal.payments.contracts.event.EventType;
import com.softfal.payments.contracts.event.PaymentProviderEvent;
import com.softfal.payments.contracts.event.ProviderPaymentStatus;
import com.softfal.payments.contracts.topic.PaymentTopics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentEventServiceTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, EventEnvelope> kafkaTemplate = mock(KafkaTemplate.class);

    private final PaymentEventService service = new PaymentEventService(
            kafkaTemplate, new PaymentApiMetrics(new SimpleMeterRegistry()), new ObjectMapper());

    @Test
    void publishesProviderEventEnvelopeKeyedAndCorrelatedByPaymentId() {
        when(kafkaTemplate.send(anyString(), anyString(), any(EventEnvelope.class)))
                .thenReturn(new CompletableFuture<>());
        PaymentProviderEvent event = new PaymentProviderEvent("pay-1", "acc-1", new BigDecimal("12.34"), "PLN",
                ProviderPaymentStatus.SUCCEEDED, "evt-1", null, Instant.parse("2026-01-01T00:00:00Z"));

        service.sendProviderEvent(event);

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<EventEnvelope> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(kafkaTemplate).send(topic.capture(), key.capture(), envelope.capture());

        assertThat(topic.getValue()).isEqualTo(PaymentTopics.PROVIDER_EVENTS);
        assertThat(key.getValue()).isEqualTo("pay-1");
        assertThat(envelope.getValue().eventType()).isEqualTo(EventType.PAYMENT_PROVIDER_EVENT.eventType());
        assertThat(envelope.getValue().correlationId()).isEqualTo("pay-1");
        assertThat(envelope.getValue().payload().get("providerStatus").asText()).isEqualTo("SUCCEEDED");
    }
}
