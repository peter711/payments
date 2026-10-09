package com.softfal.payment_processor.exception;

/**
 * Thrown when the payment gateway does not answer in time.
 *
 * <p>This is a transient failure: the {@code DefaultErrorHandler} retries the
 * record and only publishes it to the dead letter topic once the configured
 * attempts are exhausted.</p>
 */
public class GatewayTimeoutException extends RuntimeException {

    public GatewayTimeoutException(String message) {
        super(message);
    }
}
