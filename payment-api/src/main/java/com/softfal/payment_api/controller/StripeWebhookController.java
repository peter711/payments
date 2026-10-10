package com.softfal.payment_api.controller;

import com.softfal.payment_api.metrics.PaymentApiMetrics;
import com.softfal.payment_api.service.PaymentEventService;
import com.softfal.payments.contracts.event.PaymentProviderEvent;
import com.softfal.payments.gateway.InvalidWebhookSignatureException;
import com.softfal.payments.gateway.PaymentGatewayWebhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Receives inbound payment-provider webhooks, authenticates them via the
 * {@link PaymentGatewayWebhook} port and publishes the translated status update
 * for the processor to reconcile.
 *
 * <p>The endpoint only exists when {@code payment.gateway.provider=stripe}; with
 * the default mock gateway there is nothing to receive. The raw body is bound as
 * {@code byte[]} so signature verification runs over the exact bytes Stripe
 * signed, before any JSON parsing.</p>
 */
@RestController
@ConditionalOnProperty(prefix = "payment.gateway", name = "provider", havingValue = "stripe")
public class StripeWebhookController {

    static final String SIGNATURE_HEADER = "Stripe-Signature";
    static final String PATH = "/webhooks/stripe";

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final PaymentGatewayWebhook webhook;
    private final PaymentEventService paymentEventService;
    private final PaymentApiMetrics metrics;

    public StripeWebhookController(PaymentGatewayWebhook webhook,
                                   PaymentEventService paymentEventService,
                                   PaymentApiMetrics metrics) {
        this.webhook = webhook;
        this.paymentEventService = paymentEventService;
        this.metrics = metrics;
    }

    /**
     * @return {@code 400} when the signature cannot be verified; {@code 200}
     *         otherwise (including events that map to no payment, so Stripe stops
     *         retrying them)
     */
    @PostMapping(PATH)
    public ResponseEntity<Void> receive(
            @RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody byte[] body) {

        Optional<PaymentProviderEvent> parsed;
        try {
            parsed = webhook.parse(new String(body, StandardCharsets.UTF_8), signature);
        } catch (InvalidWebhookSignatureException ex) {
            metrics.webhookVerificationFailed();
            log.warn("Rejected Stripe webhook: {}", ex.getMessage());
            return ResponseEntity.badRequest().build();
        }

        if (parsed.isEmpty()) {
            metrics.webhookUnmapped();
            log.debug("Acknowledged Stripe webhook with no mapped payment");
            return ResponseEntity.ok().build();
        }

        PaymentProviderEvent event = parsed.get();
        metrics.webhookAccepted();
        paymentEventService.sendProviderEvent(event);
        return ResponseEntity.ok().build();
    }
}
