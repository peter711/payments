package com.softfal.payments.gateway;

import com.softfal.payments.gateway.stripe.StripeGatewayProperties;
import com.softfal.payments.gateway.stripe.StripePaymentGateway;
import com.stripe.StripeClient;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the Stripe adapter when {@code payment.gateway.provider=stripe}.
 *
 * <p>It is a Spring Boot auto-configuration rather than a component scan target
 * because the consuming service only scans its own package; the module owns its
 * wiring through {@code META-INF/spring/...AutoConfiguration.imports}, exactly
 * like {@code payments-observability}.</p>
 *
 * <p>When the property is absent or {@code mock}, this configuration backs off
 * and the service's own {@code MockPaymentGateway} is used, so a local run and
 * the test suite need no Stripe credentials.</p>
 */
@AutoConfiguration
@ConditionalOnClass({StripeClient.class, PaymentIntent.class})
@ConditionalOnProperty(prefix = "payment.gateway", name = "provider", havingValue = "stripe")
@EnableConfigurationProperties(StripeGatewayProperties.class)
public class StripeGatewayAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StripeGatewayAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    StripeClient stripeClient(StripeGatewayProperties properties) {
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new InvalidGatewayRequestException(
                    "payment.gateway.provider=stripe requires payment.stripe.api-key (STRIPE_API_KEY)");
        }
        log.info("Configuring the Stripe payment gateway (apiBase={})", properties.apiBase());
        return new StripeClient(apiKey);
    }

    @Bean
    @ConditionalOnMissingBean
    PaymentGateway stripePaymentGateway(StripeClient stripeClient, StripeGatewayProperties properties) {
        return new StripePaymentGateway(stripeClient, properties);
    }
}
