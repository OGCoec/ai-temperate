package com.example.temperate.service.user.aiconversation.billing.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 验证预扣与实际结算都把加权 Token 按统一基数换算为 minor，并始终向上取整。
 */
final class AiConversationQuotaCalculatorTest {

    private final AiConversationQuotaCalculator calculator =
            new AiConversationQuotaCalculator();

    @Test
    void reservationUsesOneThirdOfModelMaximumOutputRoundedUp() {
        long reserved = calculator.reservedQuota(
                0,
                128_000,
                new BigDecimal("0.75"),
                new BigDecimal("4.5"));

        assertThat(reserved).isEqualTo(241L);
    }

    @Test
    void reservationIncludesInputAfterReducingOnlyTheOutputCeiling() {
        long reserved = calculator.reservedQuota(
                100,
                128_000,
                new BigDecimal("0.75"),
                new BigDecimal("4.5"));

        assertThat(reserved).isEqualTo(241L);
        assertThat(reserved).isLessThan(5_000L);
    }

    @Test
    void actualChargeUsesCachedInputRatioWithoutDoubleChargingCachedTokens() {
        long charged = calculator.actualQuota(
                100_000,
                40_000,
                20_000,
                new BigDecimal("1.0"),
                new BigDecimal("0.25"),
                new BigDecimal("4.0"));

        assertThat(charged).isEqualTo(188L);
    }

    @Test
    void actualSettlementUsesTheFullReportedCompletionTokens() {
        long charged = calculator.actualQuota(
                0,
                0,
                128_000,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("4.5"));

        assertThat(charged).isEqualTo(720L);
    }

    @Test
    void zeroCostRemainsZeroAndAnyPositiveCostConsumesAtLeastOneMinor() {
        assertThat(calculator.reservedQuota(
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO))
                .isZero();
        assertThat(calculator.reservedQuota(
                1,
                0,
                new BigDecimal("0.00000001"),
                BigDecimal.ZERO))
                .isEqualTo(1L);
    }

    @Test
    void conversionFailsClosedWhenMinorValueCannotFitInLong() {
        assertThatThrownBy(() -> calculator.reservedQuota(
                Long.MAX_VALUE,
                0,
                new BigDecimal("200000000000000000000000"),
                BigDecimal.ZERO))
                .isInstanceOf(ArithmeticException.class);
    }
}
