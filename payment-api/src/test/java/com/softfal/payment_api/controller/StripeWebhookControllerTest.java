package com.softfal.payment_api.controller;

import com.softfal.payment_api.metrics.PaymentApiMetrics;
import com.softfal.payment_api.service.PaymentEventService;
import com.softfal.payments.contracts.event.PaymentProviderEvent;
import com.softfal.payments.contracts.event.ProviderPaymentStatus;
import com.softfal.payments.gateway.InvalidWebhookSignatureException;
import com.softfal.payments.gateway.PaymentGatewayWebhook;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StripeWebhookControllerTest {

    private static final String BODY = "{\"id\":\"evt-1\",\"type\":\"payment_intent.succeeded\"}";

    private final PaymentGatewayWebhook webhook = mock(PaymentGatewayWebhook.class);
    private final PaymentEventService paymentEventService = mock(PaymentEventService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PaymentApiMetrics metrics = new PaymentApiMetrics(registry);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new StripeWebhookController(webhook, paymentEventService, metrics))
            .build();

    @Test
    void acceptsValidWebhookAndPublishesProviderEvent() throws Exception {
        PaymentProviderEvent event = new PaymentProviderEvent("pay-1", "acc-1", new BigDecimal("12.34"), "PLN",
                ProviderPaymentStatus.SUCCEEDED, "evt-1", null, Instant.parse("2026-01-01T00:00:00Z"));
        when(webhook.parse(anyString(), anyString())).thenReturn(Optional.of(event));

        mockMvc.perform(post(StripeWebhookController.PATH)
                        .header(StripeWebhookController.SIGNATURE_HEADER, "sig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());

        verify(paymentEventService).sendProviderEvent(event);
        assertThat(counter("accepted")).isEqualTo(1.0);
    }

    @Test
    void rejectsInvalidSignatureWithBadRequest() throws Exception {
        when(webhook.parse(anyString(), anyString()))
                .thenThrow(new InvalidWebhookSignatureException("bad signature"));

        mockMvc.perform(post(StripeWebhookController.PATH)
                        .header(StripeWebhookController.SIGNATURE_HEADER, "forged")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentEventService);
        assertThat(counter("verification_failed")).isEqualTo(1.0);
    }

    @Test
    void acknowledgesUnmappedEventWithoutPublishing() throws Exception {
        when(webhook.parse(anyString(), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(post(StripeWebhookController.PATH)
                        .header(StripeWebhookController.SIGNATURE_HEADER, "sig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());

        verifyNoInteractions(paymentEventService);
        assertThat(counter("unmapped")).isEqualTo(1.0);
    }

    private double counter(String result) {
        return registry.get("payments.api.webhook.received").tag("result", result).counter().count();
    }
}
