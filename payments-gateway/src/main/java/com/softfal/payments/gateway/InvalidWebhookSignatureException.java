package com.softfal.payments.gateway;

/**
 * Thrown when an inbound provider webhook cannot be authenticated: the signature
 * is missing, malformed, or computed with a different signing secret.
 *
 * <p>The webhook edge maps this to an HTTP {@code 400} and publishes nothing, so
 * a forged request never enters the pipeline.</p>
 */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException(String message) {
        super(message);
    }

    public InvalidWebhookSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
