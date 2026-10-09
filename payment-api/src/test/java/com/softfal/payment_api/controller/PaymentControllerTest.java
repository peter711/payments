package com.softfal.payment_api.controller;

import com.softfal.payment_api.model.PaymentEventRequest;
import com.softfal.payment_api.service.PaymentEventService;
import com.softfal.payments.contracts.event.PaymentRequested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaymentControllerTest {

    private final PaymentEventService paymentEventService = mock(PaymentEventService.class);

    private final PaymentController controller = new PaymentController(paymentEventService);

    @Test
    void generatesPaymentIdWhenMissing() {
        PaymentEventRequest request =
                new PaymentEventRequest(null, "acc-1", new BigDecimal("10.00"), "PLN", Instant.now());

        ResponseEntity<PaymentEventRequest> response = controller.createPayment(null, request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().paymentId()).isNotBlank();

        PaymentRequested published = capturePublished();
        assertThat(published.paymentId()).isEqualTo(response.getBody().paymentId());
        assertThat(published.accountId()).isEqualTo("acc-1");
    }

    @Test
    void keepsCallerProvidedPaymentId() {
        PaymentEventRequest request =
                new PaymentEventRequest("pay-1", "acc-1", new BigDecimal("10.00"), "PLN", Instant.now());

        ResponseEntity<PaymentEventRequest> response = controller.createPayment(null, request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().paymentId()).isEqualTo("pay-1");
        assertThat(capturePublished().paymentId()).isEqualTo("pay-1");
    }

    @Test
    void fallsBackToPaymentIdAsCorrelationId() {
        PaymentEventRequest request =
                new PaymentEventRequest("pay-1", "acc-1", new BigDecimal("10.00"), "PLN", Instant.now());

        controller.createPayment(null, request);

        verify(paymentEventService).send(org.mockito.ArgumentMatchers.any(), eq("pay-1"));
    }

    @Test
    void usesProvidedCorrelationId() {
        PaymentEventRequest request =
                new PaymentEventRequest("pay-1", "acc-1", new BigDecimal("10.00"), "PLN", Instant.now());

        controller.createPayment("corr-123", request);

        verify(paymentEventService).send(org.mockito.ArgumentMatchers.any(), eq("corr-123"));
    }

    private PaymentRequested capturePublished() {
        ArgumentCaptor<PaymentRequested> captor = ArgumentCaptor.forClass(PaymentRequested.class);
        verify(paymentEventService).send(captor.capture(), org.mockito.ArgumentMatchers.anyString());
        return captor.getValue();
    }
}
