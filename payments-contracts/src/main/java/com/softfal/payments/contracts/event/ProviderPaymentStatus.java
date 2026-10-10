package com.softfal.payments.contracts.event;

/**
 * Provider-reported state of a payment, as carried by a
 * {@link PaymentProviderEvent}.
 *
 * <p>It is the provider's own vocabulary, kept separate from the local
 * {@code COMPLETED}/{@code FAILED} outcome so a translation step maps the two.</p>
 */
public enum ProviderPaymentStatus {

    /** The provider confirmed the payment succeeded. */
    SUCCEEDED,

    /** The provider reported the payment failed. */
    FAILED,

    /** The payment intent was canceled before it could complete. */
    CANCELED
}
