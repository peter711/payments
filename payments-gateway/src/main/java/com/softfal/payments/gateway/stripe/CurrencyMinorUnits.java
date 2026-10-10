package com.softfal.payments.gateway.stripe;

import com.softfal.payments.gateway.InvalidGatewayRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/**
 * Converts a major-unit {@link BigDecimal} amount into the minor unit Stripe
 * expects (for example {@code 12.34 PLN -> 1234} grosze).
 *
 * <p>Most currencies use two decimals, but Stripe's zero-decimal currencies
 * ({@code JPY}, {@code KRW}, ...) must be sent as whole units. An amount that
 * cannot be represented exactly in the target minor unit is a permanent error:
 * the payment is rejected rather than silently rounded.</p>
 */
public final class CurrencyMinorUnits {

    /** Currencies Stripe treats as having no fractional unit. */
    private static final Set<String> ZERO_DECIMAL_CURRENCIES = Set.of(
            "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA",
            "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF");

    private static final int DEFAULT_EXPONENT = 2;

    private CurrencyMinorUnits() {
    }

    /**
     * @return the amount in the currency's minor unit
     * @throws InvalidGatewayRequestException when the currency is missing or the
     *                                        amount has more precision than the
     *                                        currency allows
     */
    public static long toMinorUnits(BigDecimal amount, String currency) {
        int exponent = exponentFor(currency);
        try {
            return amount.movePointRight(exponent)
                    .setScale(0, RoundingMode.UNNECESSARY)
                    .longValueExact();
        } catch (ArithmeticException ex) {
            throw new InvalidGatewayRequestException(
                    "amount " + amount + " cannot be represented in " + currency.toUpperCase(Locale.ROOT)
                            + " minor units (max " + exponent + " decimal places)", ex);
        }
    }

    /** @return the number of decimal places the currency uses. */
    public static int exponentFor(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new InvalidGatewayRequestException("currency must not be blank");
        }
        return ZERO_DECIMAL_CURRENCIES.contains(currency.toUpperCase(Locale.ROOT)) ? 0 : DEFAULT_EXPONENT;
    }

    /**
     * Converts an amount from the currency's minor unit back to major units (for
     * example {@code 1234 PLN -> 12.34}), the inverse of {@link #toMinorUnits}.
     *
     * @throws InvalidGatewayRequestException when the currency is missing
     */
    public static BigDecimal fromMinorUnits(long minorUnits, String currency) {
        return BigDecimal.valueOf(minorUnits, exponentFor(currency));
    }
}
