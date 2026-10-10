package com.softfal.payment_processor;

import com.softfal.payments.gateway.PaymentGateway;
import com.softfal.payments.gateway.stripe.StripePaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@code payments-gateway} auto-configuration is discovered by
 * the real service (through its {@code AutoConfiguration.imports}) and replaces
 * the mock when {@code payment.gateway.provider=stripe}.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-processor-stripe-context;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "payment.gateway.provider=stripe",
        "payment.stripe.api-key=sk_test_dummy"
})
@EmbeddedKafka(partitions = 1,
        topics = {"payments.requested"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class StripeGatewayContextTests {

    @Autowired
    private PaymentGateway paymentGateway;

    @Test
    void usesStripeAdapterWhenProviderIsStripe() {
        assertThat(paymentGateway).isInstanceOf(StripePaymentGateway.class);
    }
}
