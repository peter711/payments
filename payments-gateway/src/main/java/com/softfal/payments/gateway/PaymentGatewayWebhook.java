package com.softfal.payments.gateway;

import com.softfal.payments.contracts.event.PaymentProviderEvent;

import java.util.Optional;

/**
 * Port abstracting inbound payment-provider webhooks. The adapter authenticates
 * the raw request body against the provider signature and translates a supported
 * event into the domain {@link PaymentProviderEvent}; the HTTP edge only depends
 * on this interface, never on a provider SDK.
 */
public interface PaymentGatewayWebhook {

    /**
     * Authenticates and translates an inbound webhook request.
     *
     * @param rawBody        the exact request body as received; signature
     *                       verification must run over these bytes, not a re-encoded copy
     * @param signatureHeader the provider signature header ({@code Stripe-Signature})
     * @return the translated provider event, or empty when the event is not
     *         supported or carries no resolvable payment
     * @throws InvalidWebhookSignatureException when the signature cannot be verified
     */
    Optional<PaymentProviderEvent> parse(String rawBody, String signatureHeader);
}
