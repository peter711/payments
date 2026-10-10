package com.softfal.payments.gateway.stripe;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.softfal.payments.contracts.event.PaymentRequested;
import com.softfal.payments.gateway.GatewayTimeoutException;
import com.softfal.payments.gateway.InvalidGatewayRequestException;
import com.softfal.payments.gateway.PaymentGatewayResult;
import com.stripe.StripeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the real {@link StripePaymentGateway} against an in-process HTTP server
 * standing in for {@code api.stripe.com}, so the request shape (idempotency key,
 * amount in minor units) and the response mapping are exercised without a real
 * Stripe account.
 */
class StripePaymentGatewayTest {

    private static final String PAYMENT_INTENTS_PATH = "/v1/payment_intents";

    private static final PaymentRequested EVENT = new PaymentRequested(
            "pay-1", "acc-1", new BigDecimal("12.34"), "PLN", Instant.now());

    private WireMockServer server;
    private StripePaymentGateway gateway;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        // maxNetworkRetries=0 keeps the failure tests fast and deterministic.
        gateway = new StripePaymentGateway(
                new StripeClient("sk_test_123"),
                new StripeGatewayProperties("sk_test_123", server.baseUrl(), "pm_card_visa", 2000, 0, null));
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void returnsSuccessAndSendsIdempotencyKeyAndMinorUnitsOnSucceededIntent() {
        stubPaymentIntent("succeeded");

        PaymentGatewayResult result = gateway.callPaymentGateway(EVENT);

        assertThat(result.successful()).isTrue();
        assertThat(result.reason()).isNull();
        server.verify(postRequestedFor(urlEqualTo(PAYMENT_INTENTS_PATH))
                .withHeader("Idempotency-Key", equalTo("pay-1"))
                .withRequestBody(containing("amount=1234"))
                .withRequestBody(containing("currency=pln"))
                .withRequestBody(containing("confirm=true"))
                .withRequestBody(containing("automatic_payment_methods[enabled]=true"))
                .withRequestBody(containing("automatic_payment_methods[allow_redirects]=never")));
    }

    @Test
    void returnsFailureWhenIntentRequiresCustomerAction() {
        stubPaymentIntent("requires_action");

        PaymentGatewayResult result = gateway.callPaymentGateway(EVENT);

        assertThat(result.successful()).isFalse();
        assertThat(result.reason()).contains("requires_action");
    }

    @Test
    void mapsCardDeclineToFailureWithDeclineCode() {
        server.stubFor(post(urlEqualTo(PAYMENT_INTENTS_PATH)).willReturn(aResponse()
                .withStatus(402)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error":{"type":"card_error","code":"card_declined",
                        "decline_code":"generic_decline","message":"Your card was declined."}}
                        """)));

        PaymentGatewayResult result = gateway.callPaymentGateway(EVENT);

        assertThat(result.successful()).isFalse();
        assertThat(result.reason()).contains("generic_decline");
    }

    @Test
    void mapsRateLimitToTransientTimeout() {
        server.stubFor(post(urlEqualTo(PAYMENT_INTENTS_PATH)).willReturn(aResponse()
                .withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error":{"type":"rate_limit_error","message":"Too many requests"}}
                        """)));

        assertThatThrownBy(() -> gateway.callPaymentGateway(EVENT))
                .isInstanceOf(GatewayTimeoutException.class);
    }

    @Test
    void mapsAuthenticationFailureToPermanentError() {
        server.stubFor(post(urlEqualTo(PAYMENT_INTENTS_PATH)).willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error":{"type":"authentication_error","message":"Invalid API Key provided"}}
                        """)));

        assertThatThrownBy(() -> gateway.callPaymentGateway(EVENT))
                .isInstanceOf(InvalidGatewayRequestException.class);
    }

    private void stubPaymentIntent(String status) {
        server.stubFor(post(urlEqualTo(PAYMENT_INTENTS_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"pi_123","object":"payment_intent","status":"%s"}
                        """.formatted(status))));
    }
}
