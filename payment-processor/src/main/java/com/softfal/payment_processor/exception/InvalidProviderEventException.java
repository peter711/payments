package com.softfal.payment_processor.exception;

/**
 * Thrown when a {@code payment.provider_event} cannot be read or is missing a
 * mandatory identifier. It is a permanent failure: retrying cannot fix a
 * malformed record, so it is routed straight to the provider-events dead letter
 * topic.
 */
public class InvalidProviderEventException extends RuntimeException {

    public InvalidProviderEventException(String message) {
        super(message);
    }
}
