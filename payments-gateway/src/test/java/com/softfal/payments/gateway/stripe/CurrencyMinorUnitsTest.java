package com.softfal.payments.gateway.stripe;

import com.softfal.payments.gateway.InvalidGatewayRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyMinorUnitsTest {

    @Test
    void convertsTwoDecimalCurrencyToMinorUnits() {
        assertThat(CurrencyMinorUnits.toMinorUnits(new BigDecimal("12.34"), "PLN")).isEqualTo(1234L);
        assertThat(CurrencyMinorUnits.toMinorUnits(new BigDecimal("10"), "usd")).isEqualTo(1000L);
    }

    @Test
    void convertsZeroDecimalCurrencyAsWholeUnits() {
        assertThat(CurrencyMinorUnits.toMinorUnits(new BigDecimal("1000"), "jpy")).isEqualTo(1000L);
        assertThat(CurrencyMinorUnits.toMinorUnits(new BigDecimal("500"), "KRW")).isEqualTo(500L);
    }

    @Test
    void convertsMinorUnitsBackToMajorUnits() {
        assertThat(CurrencyMinorUnits.fromMinorUnits(1234L, "PLN")).isEqualByComparingTo("12.34");
        assertThat(CurrencyMinorUnits.fromMinorUnits(1000L, "jpy")).isEqualByComparingTo("1000");
    }

    @Test
    void rejectsAmountWithMorePrecisionThanTheCurrencyAllows() {
        assertThatThrownBy(() -> CurrencyMinorUnits.toMinorUnits(new BigDecimal("10.005"), "PLN"))
                .isInstanceOf(InvalidGatewayRequestException.class)
                .hasMessageContaining("minor units");
    }

    @Test
    void rejectsBlankCurrency() {
        assertThatThrownBy(() -> CurrencyMinorUnits.toMinorUnits(new BigDecimal("10.00"), " "))
                .isInstanceOf(InvalidGatewayRequestException.class)
                .hasMessageContaining("currency");
    }
}
