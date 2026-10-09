package com.softfal.payment_processor.exception;

/**
 * Thrown when a {@code PaymentRequested} event fails validation before it reaches the
 * gateway (e.g. non-positive amount or missing required fields).
 *
 * <p>This is a permanent failure: retrying cannot succeed, so the
 * {@code DefaultErrorHandler} must route these records straight to the dead
 * letter topic without retries.</p>
 */
public class InvalidPaymentException extends RuntimeException {

    public InvalidPaymentException(String message) {
        super(message);
    }
}
