package com.softfal.payments.gateway.stripe;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Stripe adapter, bound from the {@code payment.stripe}
 * prefix.
 *
 * <p>The API key is left optional here because the adapter is only created when
 * {@code payment.gateway.provider=stripe}; the auto-configuration fails fast
 * when the adapter is enabled without a key, so a local mock run never needs
 * Stripe credentials.</p>
 *
 * @param apiKey            secret key ({@code sk_test_...} for a sandbox,
 *                          {@code sk_live_...} in production)
 * @param apiBase           API base URL; overridable so tests can point at a
 *                          local HTTP server
 * @param paymentMethod     payment method attached to the confirmed
 *                          PaymentIntent (a test method such as
 *                          {@code pm_card_visa} in a sandbox)
 * @param timeoutMs         connect and read timeout for a Stripe call
 * @param maxNetworkRetries automatic SDK retries of a failed request (keep low;
 *                          Kafka already retries the record)
 * @param webhookSecret     signing secret ({@code whsec_...}) used to
 *                          authenticate inbound webhooks; required only when the
 *                          webhook adapter is enabled
 */
@ConfigurationProperties(prefix = "payment.stripe")
public record StripeGatewayProperties(
        String apiKey,
        String apiBase,
        String paymentMethod,
        Integer timeoutMs,
        Integer maxNetworkRetries,
        String webhookSecret
) {

    private static final String DEFAULT_API_BASE = "https://api.stripe.com";
    private static final String DEFAULT_PAYMENT_METHOD = "pm_card_visa";

    public StripeGatewayProperties {
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = DEFAULT_API_BASE;
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            paymentMethod = DEFAULT_PAYMENT_METHOD;
        }
        if (timeoutMs == null) {
            timeoutMs = 5000;
        }
        if (maxNetworkRetries == null) {
            maxNetworkRetries = 2;
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("payment.stripe.timeout-ms must be positive, was " + timeoutMs);
        }
        if (maxNetworkRetries < 0) {
            throw new IllegalArgumentException(
                    "payment.stripe.max-network-retries must not be negative, was " + maxNetworkRetries);
        }
    }
}
