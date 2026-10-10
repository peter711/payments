package com.softfal.payments.gateway;

/**
 * Thrown when the payment gateway does not answer in time or fails transiently.
 *
 * <p>This is a transient failure: the consumer's error handler retries the
 * record and only publishes it to the dead letter topic once the configured
 * attempts are exhausted.</p>
 */
public class GatewayTimeoutException extends RuntimeException {

    public GatewayTimeoutException(String message) {
        super(message);
    }
}
