package com.softfal.payments.gateway.stripe;

import com.softfal.payments.contracts.event.PaymentProviderEvent;
import com.softfal.payments.contracts.event.ProviderPaymentStatus;
import com.softfal.payments.gateway.InvalidWebhookSignatureException;
import com.softfal.payments.gateway.PaymentGatewayWebhook;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@link PaymentGatewayWebhook} adapter for Stripe.
 *
 * <p>The signature is verified over the raw body with the Stripe SDK before the
 * payload is inspected, so an unauthenticated request never produces an event.
 * Only the payment-intent events that carry a terminal outcome are translated;
 * anything else (including events without a resolvable {@code paymentId}) is
 * reported as empty so the edge can acknowledge it without side effects.</p>
 */
public class StripePaymentGatewayWebhook implements PaymentGatewayWebhook {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGatewayWebhook.class);

    private static final Map<String, ProviderPaymentStatus> SUPPORTED_EVENTS = Map.of(
            "payment_intent.succeeded", ProviderPaymentStatus.SUCCEEDED,
            "payment_intent.payment_failed", ProviderPaymentStatus.FAILED,
            "payment_intent.canceled", ProviderPaymentStatus.CANCELED);

    private final StripeGatewayProperties properties;

    public StripePaymentGatewayWebhook(StripeGatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<PaymentProviderEvent> parse(String rawBody, String signatureHeader) {
        Event event = verify(rawBody, signatureHeader);

        ProviderPaymentStatus status = SUPPORTED_EVENTS.get(event.getType());
        if (status == null) {
            log.debug("Ignoring unsupported Stripe event type={} id={}", event.getType(), event.getId());
            return Optional.empty();
        }

        PaymentIntent intent = paymentIntent(event);
        if (intent == null) {
            return Optional.empty();
        }

        String paymentId = metadata(intent, "paymentId");
        String accountId = metadata(intent, "accountId");
        if (isBlank(paymentId) || isBlank(accountId)) {
            log.warn("Ignoring Stripe event id={} type={}: paymentId/accountId metadata missing",
                    event.getId(), event.getType());
            return Optional.empty();
        }

        String currency = intent.getCurrency() == null
                ? null
                : intent.getCurrency().toUpperCase(Locale.ROOT);
        var amount = intent.getAmount() == null || currency == null
                ? null
                : CurrencyMinorUnits.fromMinorUnits(intent.getAmount(), currency);

        return Optional.of(new PaymentProviderEvent(
                paymentId,
                accountId,
                amount,
                currency,
                status,
                event.getId(),
                reason(intent, status),
                occurredAt(event)));
    }

    private Event verify(String rawBody, String signatureHeader) {
        try {
            return Webhook.constructEvent(rawBody, signatureHeader, properties.webhookSecret());
        } catch (SignatureVerificationException ex) {
            throw new InvalidWebhookSignatureException("Stripe webhook signature verification failed", ex);
        }
    }

    private static PaymentIntent paymentIntent(Event event) {
        StripeObject object = dataObject(event);
        return object instanceof PaymentIntent intent ? intent : null;
    }

    /**
     * Prefers the SDK's version-checked deserialization and falls back to the raw
     * object when the event carries an API version the SDK does not recognize
     * (or none at all, as in tests), which would otherwise yield no object.
     */
    private static StripeObject dataObject(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        try {
            Optional<StripeObject> object = deserializer.getObject();
            if (object.isPresent()) {
                return object.get();
            }
        } catch (RuntimeException ex) {
            log.debug("Stripe event id={} has no compatible api version; using the raw data object", event.getId());
        }
        try {
            return deserializer.deserializeUnsafe();
        } catch (EventDataObjectDeserializationException ex) {
            log.warn("Could not read Stripe event id={} data object: {}", event.getId(), ex.getMessage());
            return null;
        }
    }

    private static String reason(PaymentIntent intent, ProviderPaymentStatus status) {
        if (status == ProviderPaymentStatus.FAILED && intent.getLastPaymentError() != null) {
            return intent.getLastPaymentError().getMessage();
        }
        if (status == ProviderPaymentStatus.CANCELED) {
            String cancellationReason = intent.getCancellationReason();
            return cancellationReason == null ? "canceled" : cancellationReason;
        }
        return null;
    }

    private static Instant occurredAt(Event event) {
        return event.getCreated() == null ? Instant.now() : Instant.ofEpochSecond(event.getCreated());
    }

    private static String metadata(PaymentIntent intent, String key) {
        Map<String, String> metadata = intent.getMetadata();
        return metadata == null ? null : metadata.get(key);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
