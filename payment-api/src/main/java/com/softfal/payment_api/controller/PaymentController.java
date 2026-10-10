package com.softfal.payment_api.controller;

import com.softfal.payment_api.model.PaymentEventRequest;
import com.softfal.payment_api.service.PaymentEventService;
import com.softfal.payments.contracts.event.PaymentRequested;
import com.softfal.payments.observability.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Exposes an HTTP entry point for publishing payment events.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentEventService paymentEventService;

    public PaymentController(PaymentEventService paymentEventService) {
        this.paymentEventService = paymentEventService;
    }

    /**
     * Accepts a payment request and publishes it to Kafka. If the caller did not
     * supply a {@code paymentId}, one is generated so the event always carries the
     * idempotency key the processor relies on.
     *
     * <p>The optional {@value #CORRELATION_ID_HEADER} header starts or continues a
     * business flow; when absent, the payment id is used so every flow still has a
     * correlation id.</p>
     *
     * @return {@code 202 Accepted} with the accepted payload
     */
    @PostMapping
    public ResponseEntity<PaymentEventRequest> createPayment(
            @RequestHeader(name = CORRELATION_ID_HEADER, required = false) String correlationId,
            @RequestBody PaymentEventRequest request) {

        PaymentEventRequest accepted = withPaymentId(request);
        String effectiveCorrelationId = effectiveCorrelationId(correlationId, accepted.paymentId());
        try (var logContext = LogContext.scope()
                .correlationId(effectiveCorrelationId)
                .paymentId(accepted.paymentId())
                .accountId(accepted.accountId())) {
            log.info("Received payment request for paymentId={} correlationId={}",
                    accepted.paymentId(), effectiveCorrelationId);

            paymentEventService.send(toContract(accepted), effectiveCorrelationId);
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    /**
     * Maps the HTTP request model onto the shared wire contract, keeping the REST
     * schema and the Kafka event decoupled so either can evolve independently.
     */
    private static PaymentRequested toContract(PaymentEventRequest request) {
        return new PaymentRequested(
                request.paymentId(),
                request.accountId(),
                request.amount(),
                request.currency(),
                request.createdAt());
    }

    private static String effectiveCorrelationId(String correlationId, String paymentId) {
        return correlationId == null || correlationId.isBlank() ? paymentId : correlationId;
    }

    private PaymentEventRequest withPaymentId(PaymentEventRequest request) {
        if (request.paymentId() != null && !request.paymentId().isBlank()) {
            return request;
        }
        return new PaymentEventRequest(
                UUID.randomUUID().toString(),
                request.accountId(),
                request.amount(),
                request.currency(),
                request.createdAt());
    }
}
