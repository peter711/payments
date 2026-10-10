package com.softfal.payments.gateway;

import com.softfal.payments.gateway.stripe.StripePaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StripeGatewayAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(StripeGatewayAutoConfiguration.class);

    @Test
    void registersStripeGatewayWhenProviderIsStripeAndKeyIsPresent() {
        contextRunner
                .withPropertyValues("payment.gateway.provider=stripe", "payment.stripe.api-key=sk_test_123")
                .run(context -> assertThat(context)
                        .hasSingleBean(PaymentGateway.class)
                        .getBean(PaymentGateway.class)
                        .isInstanceOf(StripePaymentGateway.class));
    }

    @Test
    void backsOffWhenProviderIsMock() {
        contextRunner
                .withPropertyValues("payment.gateway.provider=mock")
                .run(context -> assertThat(context).doesNotHaveBean(PaymentGateway.class));
    }

    @Test
    void backsOffWhenProviderIsAbsent() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(PaymentGateway.class));
    }

    @Test
    void failsFastWhenProviderIsStripeWithoutApiKey() {
        contextRunner
                .withPropertyValues("payment.gateway.provider=stripe")
                .run(context -> assertThat(context).hasFailed());
    }
}
