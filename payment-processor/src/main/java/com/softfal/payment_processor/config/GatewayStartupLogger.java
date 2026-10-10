package com.softfal.payment_processor.config;

import com.softfal.payments.gateway.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logs which payment gateway was selected once the application is ready.
 *
 * <p>The provider can come from the {@code PAYMENT_GATEWAY_PROVIDER} environment
 * variable, a {@code -DPAYMENT_GATEWAY_PROVIDER=...} system property or the
 * {@code payment.gateway.provider} setting. Printing both the raw input and the
 * resolved value (plus the active adapter class) makes it obvious whether the
 * mock or Stripe is in use.</p>
 */
@Component
public class GatewayStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(GatewayStartupLogger.class);

    private final Environment environment;
    private final PaymentGateway paymentGateway;

    public GatewayStartupLogger(Environment environment, PaymentGateway paymentGateway) {
        this.environment = environment;
        this.paymentGateway = paymentGateway;
    }

    @EventListener(ApplicationReadyEvent.class)
    void logActiveGateway() {
        String requested = environment.getProperty("PAYMENT_GATEWAY_PROVIDER");
        String resolved = environment.getProperty("payment.gateway.provider");
        log.info("Payment gateway provider: PAYMENT_GATEWAY_PROVIDER={} -> payment.gateway.provider={} (adapter={})",
                requested, resolved, paymentGateway.getClass().getSimpleName());
    }
}
