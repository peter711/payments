package com.softfal.payments.gateway;

import com.softfal.payments.contracts.event.PaymentRequested;

/**
 * Port abstracting the payment gateway. Adapters live either in a service (the
 * local {@code MockPaymentGateway}) or in this module (the Stripe adapter); the
 * listener only depends on this interface.
 *
 * <p>Implementations must be idempotent per {@link PaymentRequested#paymentId()}:
 * calling the gateway twice for the same payment must not charge the account
 * twice. A real client sends {@code paymentId} as the provider's
 * {@code Idempotency-Key} header.</p>
 */
public interface PaymentGateway {

    /**
     * Calls the payment gateway for the given payment event.
     *
     * @param event the payment to process; its {@code paymentId} acts as the
     *              idempotency key
     * @return the gateway outcome
     * @throws GatewayTimeoutException          when the gateway fails transiently
     *                                          and the record may be retried
     * @throws InvalidGatewayRequestException   when the request cannot succeed on
     *                                          retry (configuration or malformed
     *                                          request)
     */
    PaymentGatewayResult callPaymentGateway(PaymentRequested event);
}
