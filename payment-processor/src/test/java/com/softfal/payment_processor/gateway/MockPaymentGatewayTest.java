package com.softfal.payment_processor.gateway;

import com.softfal.payments.contracts.event.PaymentRequested;
import com.softfal.payments.gateway.GatewayTimeoutException;
import com.softfal.payments.gateway.PaymentGatewayResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPaymentGatewayTest {

    private static final PaymentRequested EVENT = new PaymentRequested(
            "pay-1", "acc-1", new BigDecimal("10.00"), "PLN", Instant.now());

    @Test
    void returnsSuccessWhenSuccessRateIsOne() {
        MockPaymentGateway gateway = new MockPaymentGateway(new PaymentMockProperties(1.0, 0L), new InMemoryGatewayChargeStore());

        PaymentGatewayResult result = gateway.callPaymentGateway(EVENT);

        assertThat(result.successful()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void returnsFailureWhenSuccessRateIsZero() {
        MockPaymentGateway gateway = new MockPaymentGateway(new PaymentMockProperties(0.0, 0L), new InMemoryGatewayChargeStore());

        PaymentGatewayResult result = gateway.callPaymentGateway(EVENT);

        assertThat(result.successful()).isFalse();
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void flakyAccountTimesOutTwiceThenSucceeds() {
        MockPaymentGateway gateway = new MockPaymentGateway(new PaymentMockProperties(0.0, 0L), new InMemoryGatewayChargeStore());
        PaymentRequested event = new PaymentRequested(
                "pay-flaky", MockPaymentGateway.FLAKY_ACCOUNT, new BigDecimal("10.00"), "PLN", Instant.now());

        assertThatThrownBy(() -> gateway.callPaymentGateway(event))
                .isInstanceOf(GatewayTimeoutException.class);
        assertThatThrownBy(() -> gateway.callPaymentGateway(event))
                .isInstanceOf(GatewayTimeoutException.class);

        PaymentGatewayResult result = gateway.callPaymentGateway(event);

        assertThat(result.successful()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void alwaysTimeoutAccountAlwaysThrows() {
        MockPaymentGateway gateway = new MockPaymentGateway(new PaymentMockProperties(1.0, 0L), new InMemoryGatewayChargeStore());
        PaymentRequested event = new PaymentRequested(
                "pay-down", MockPaymentGateway.ALWAYS_TIMEOUT_ACCOUNT, new BigDecimal("10.00"), "PLN", Instant.now());

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> gateway.callPaymentGateway(event))
                    .isInstanceOf(GatewayTimeoutException.class);
        }
    }

    @Test
    void flakyAttemptsAreTrackedPerPayment() {
        MockPaymentGateway gateway = new MockPaymentGateway(new PaymentMockProperties(0.0, 0L), new InMemoryGatewayChargeStore());
        PaymentRequested first = new PaymentRequested(
                "pay-a", MockPaymentGateway.FLAKY_ACCOUNT, new BigDecimal("10.00"), "PLN", Instant.now());
        PaymentRequested second = new PaymentRequested(
                "pay-b", MockPaymentGateway.FLAKY_ACCOUNT, new BigDecimal("10.00"), "PLN", Instant.now());

        assertThatThrownBy(() -> gateway.callPaymentGateway(first)).isInstanceOf(GatewayTimeoutException.class);
        assertThatThrownBy(() -> gateway.callPaymentGateway(second)).isInstanceOf(GatewayTimeoutException.class);
        assertThatThrownBy(() -> gateway.callPaymentGateway(first)).isInstanceOf(GatewayTimeoutException.class);
        assertThatThrownBy(() -> gateway.callPaymentGateway(second)).isInstanceOf(GatewayTimeoutException.class);

        // Third attempt succeeds independently for each payment.
        assertThat(gateway.callPaymentGateway(second).successful()).isTrue();
        assertThat(gateway.callPaymentGateway(first).successful()).isTrue();
    }

    @Test
    void returnsStoredResultForAlreadyChargedPayment() {
        GatewayChargeStore store = new InMemoryGatewayChargeStore();
        // Same ledger, different behaviour: the second gateway would always decline,
        // so a successful result can only come from the stored charge.
        MockPaymentGateway firstGateway = new MockPaymentGateway(new PaymentMockProperties(1.0, 0L), store);
        MockPaymentGateway secondGateway = new MockPaymentGateway(new PaymentMockProperties(0.0, 0L), store);

        PaymentGatewayResult firstResult = firstGateway.callPaymentGateway(EVENT);
        PaymentGatewayResult repeatedResult = secondGateway.callPaymentGateway(EVENT);

        assertThat(firstResult.successful()).isTrue();
        assertThat(repeatedResult.successful()).isTrue();
        assertThat(repeatedResult.reason()).isNull();
    }
}
