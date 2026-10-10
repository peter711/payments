package com.softfal.payments.gateway;

/**
 * Thrown when a gateway request cannot succeed on retry: a malformed request, an
 * unsupported amount/currency combination or an authentication/configuration
 * error.
 *
 * <p>Consumers treat this as a permanent failure and route the record straight to
 * the dead letter topic without exhausting the retries.</p>
 */
public class InvalidGatewayRequestException extends RuntimeException {

    public InvalidGatewayRequestException(String message) {
        super(message);
    }

    public InvalidGatewayRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
