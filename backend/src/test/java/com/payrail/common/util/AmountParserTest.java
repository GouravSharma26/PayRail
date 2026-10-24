package com.payrail.common.util;

import com.payrail.common.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountParserTest {

    @Test
    @DisplayName("Should successfully parse and normalize valid 2-decimal string")
    void shouldParseValidTwoDecimalString() {
        BigDecimal result = AmountParser.parseAmount("amount", "125.40", false);
        assertThat(result).isEqualByComparingTo(new BigDecimal("125.40"));
        assertThat(result.scale()).isEqualTo(2);
        assertThat(AmountParser.formatAmount(result)).isEqualTo("125.40");
    }

    @Test
    @DisplayName("Should normalize integer and single-decimal string to 2 decimals")
    void shouldNormalizeSingleDecimalAndInteger() {
        BigDecimal fromInt = AmountParser.parseAmount("amount", "100", false);
        assertThat(fromInt).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(fromInt.scale()).isEqualTo(2);
        assertThat(AmountParser.formatAmount(fromInt)).isEqualTo("100.00");

        BigDecimal fromSingle = AmountParser.parseAmount("amount", "100.5", false);
        assertThat(fromSingle).isEqualByComparingTo(new BigDecimal("100.50"));
        assertThat(fromSingle.scale()).isEqualTo(2);
        assertThat(AmountParser.formatAmount(fromSingle)).isEqualTo("100.50");
    }

    @Test
    @DisplayName("Should allow zero when allowZero is true, reject zero when allowZero is false")
    void shouldHandleZeroCorrectly() {
        BigDecimal zero = AmountParser.parseAmount("openingBalance", "0.00", true);
        assertThat(zero).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(zero.scale()).isEqualTo(2);

        assertThatThrownBy(() -> AmountParser.parseAmount("amount", "0.00", false))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("strictly positive");

        assertThatThrownBy(() -> AmountParser.parseAmount("amount", "0", false))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("strictly positive");
    }

    @ParameterizedTest
    @ValueSource(strings = {"100.123", "0.001", "5.9999", "12.345"})
    @DisplayName("Should reject amounts with more than two decimal places")
    void shouldRejectExcessScale(String input) {
        assertThatThrownBy(() -> AmountParser.parseAmount("amount", input, false))
                .isInstanceOf(ValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-10.00", "-0.01", "-100", "+50.00", "1e5", "2E3", "abc", "12.34.56", "", "   "})
    @DisplayName("Should reject invalid formats, negative numbers, and scientific notation")
    void shouldRejectInvalidFormats(String input) {
        assertThatThrownBy(() -> AmountParser.parseAmount("amount", input, false))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Should reject null amount")
    void shouldRejectNullAmount() {
        assertThatThrownBy(() -> AmountParser.parseAmount("amount", null, false))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Amount is required");
    }
}
