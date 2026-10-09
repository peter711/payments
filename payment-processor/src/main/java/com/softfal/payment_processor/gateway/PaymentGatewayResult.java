package com.softfal.payment_processor.gateway;

/**
 * Outcome of a {@link PaymentGateway#callPaymentGateway} invocation.
 *
 * @param successful whether the gateway accepted the payment
 * @param reason     failure reason, {@code null} on success
 */
public record PaymentGatewayResult(boolean successful, String reason) {

    public static PaymentGatewayResult success() {
        return new PaymentGatewayResult(true, null);
    }

    public static PaymentGatewayResult failure(String reason) {
        return new PaymentGatewayResult(false, reason);
    }
}
