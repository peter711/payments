package com.softfal.payments.gateway.stripe;

import com.softfal.payments.contracts.event.PaymentProviderEvent;
import com.softfal.payments.contracts.event.ProviderPaymentStatus;
import com.softfal.payments.gateway.InvalidWebhookSignatureException;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the real {@link StripePaymentGatewayWebhook} with correctly and
 * incorrectly signed payloads, so signature verification and the
 * event-to-provider-event translation are exercised without a Stripe account.
 */
class StripePaymentGatewayWebhookTest {

    private static final String SECRET = "whsec_test";

    private final StripePaymentGatewayWebhook webhook = new StripePaymentGatewayWebhook(
            new StripeGatewayProperties("sk_test_123", null, null, null, null, SECRET));

    @Test
    void translatesSignedSucceededEvent() throws Exception {
        String payload = event("evt-1", "payment_intent.succeeded", """
                {"id":"pi_1","object":"payment_intent","amount":1234,"currency":"pln","status":"succeeded",
                 "metadata":{"paymentId":"pay-1","accountId":"acc-1"}}""");

        Optional<PaymentProviderEvent> parsed = webhook.parse(payload, signature(payload));

        assertThat(parsed).isPresent();
        PaymentProviderEvent event = parsed.get();
        assertThat(event.providerStatus()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
        assertThat(event.paymentId()).isEqualTo("pay-1");
        assertThat(event.accountId()).isEqualTo("acc-1");
        assertThat(event.amount()).isEqualByComparingTo("12.34");
        assertThat(event.currency()).isEqualTo("PLN");
        assertThat(event.providerEventId()).isEqualTo("evt-1");
        assertThat(event.reason()).isNull();
    }

    @Test
    void translatesFailedEventWithDeclineReason() throws Exception {
        String payload = event("evt-2", "payment_intent.payment_failed", """
                {"id":"pi_2","object":"payment_intent","amount":2000,"currency":"pln","status":"requires_payment_method",
                 "last_payment_error":{"message":"Your card was declined."},
                 "metadata":{"paymentId":"pay-2","accountId":"acc-2"}}""");

        PaymentProviderEvent event = webhook.parse(payload, signature(payload)).orElseThrow();

        assertThat(event.providerStatus()).isEqualTo(ProviderPaymentStatus.FAILED);
        assertThat(event.reason()).isEqualTo("Your card was declined.");
    }

    @Test
    void translatesCanceledEvent() throws Exception {
        String payload = event("evt-3", "payment_intent.canceled", """
                {"id":"pi_3","object":"payment_intent","amount":500,"currency":"usd","status":"canceled",
                 "cancellation_reason":"abandoned",
                 "metadata":{"paymentId":"pay-3","accountId":"acc-3"}}""");

        PaymentProviderEvent event = webhook.parse(payload, signature(payload)).orElseThrow();

        assertThat(event.providerStatus()).isEqualTo(ProviderPaymentStatus.CANCELED);
        assertThat(event.reason()).isEqualTo("abandoned");
        assertThat(event.currency()).isEqualTo("USD");
    }

    @Test
    void rejectsForgedSignature() {
        String payload = event("evt-4", "payment_intent.succeeded",
                "{\"id\":\"pi_4\",\"object\":\"payment_intent\",\"metadata\":{\"paymentId\":\"pay-4\",\"accountId\":\"acc-4\"}}");

        assertThatThrownBy(() -> webhook.parse(payload,
                "t=1700000000,v1=0000000000000000000000000000000000000000000000000000000000000000"))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void ignoresUnsupportedEventType() throws Exception {
        String payload = event("evt-5", "charge.refunded",
                "{\"id\":\"ch_1\",\"object\":\"charge\"}");

        assertThat(webhook.parse(payload, signature(payload))).isEmpty();
    }

    @Test
    void ignoresEventWithoutPaymentMetadata() throws Exception {
        String payload = event("evt-6", "payment_intent.succeeded",
                "{\"id\":\"pi_6\",\"object\":\"payment_intent\",\"amount\":100,\"currency\":\"pln\",\"metadata\":{}}");

        assertThat(webhook.parse(payload, signature(payload))).isEmpty();
    }

    private static String signature(String payload) throws Exception {
        return Webhook.Signature.generateSignatureHeader(payload, SECRET);
    }

    private static String event(String id, String type, String dataObject) {
        return """
                {"id":"%s","object":"event","type":"%s","created":1700000000,"livemode":false,
                 "data":{"object":%s}}""".formatted(id, type, dataObject);
    }
}
