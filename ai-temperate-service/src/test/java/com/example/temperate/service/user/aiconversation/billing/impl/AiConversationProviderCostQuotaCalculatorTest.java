package com.example.temperate.service.user.aiconversation.billing.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 验证 xAI 美元成本 ticks 使用整数向上取整换算为项目额度，且不会通过乘法引入溢出。
 */
final class AiConversationProviderCostQuotaCalculatorTest {

    private final AiConversationProviderCostQuotaCalculator calculator =
            new AiConversationProviderCostQuotaCalculator();

    @Test
    void convertsOfficialCostTicksToQuotaMinor() {
        assertThat(calculator.actualQuotaMinor(0L)).isZero();
        assertThat(calculator.actualQuotaMinor(1L)).isEqualTo(100L);
        assertThat(calculator.actualQuotaMinor(100_000_000L)).isEqualTo(100L);
        assertThat(calculator.actualQuotaMinor(100_000_001L)).isEqualTo(200L);
        assertThat(calculator.actualQuotaMinor(200_000_000L)).isEqualTo(200L);
        assertThat(calculator.reservedQuotaMinor((short) 3)).isEqualTo(300L);
    }

    @Test
    void rejectsNegativeTicksAndInvalidOutputCounts() {
        assertThatThrownBy(() -> calculator.actualQuotaMinor(-1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.reservedQuotaMinor((short) 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
