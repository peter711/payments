package com.softfal.payment_processor.gateway;

import com.softfal.payments.contracts.event.PaymentRequested;

/**
 * Port abstracting the payment gateway. The current implementation is a local
 * mock; a real HTTP client can replace it later without touching the listener.
 *
 * <p>Implementations must be idempotent per {@link PaymentRequested#paymentId()}:
 * calling the gateway twice for the same payment must not charge the account
 * twice. A real client would send {@code paymentId} as the provider's
 * {@code Idempotency-Key} header.</p>
 */
public interface PaymentGateway {

    /**
     * Calls the payment gateway for the given payment event.
     *
     * @param event the payment to process; its {@code paymentId} acts as the
     *              idempotency key
     * @return the gateway outcome
     */
    PaymentGatewayResult callPaymentGateway(PaymentRequested event);
}
