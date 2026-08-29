package com.example.temperate.service.user.membership.payment.observability;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定 Redis 写入分层计时的 64 条批量与六 lane 低基数边界。
 */
final class MembershipPaymentRedisWriteBreakdownTest {

    @Test
    void acceptsLastProductionBatchAndLane() {
        assertThatCode(() -> breakdown(64, 5)).doesNotThrowAnyException();
    }

    @Test
    void rejectsBatchOrLaneOutsideProductionEvidenceContract() {
        assertThatThrownBy(() -> breakdown(65, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> breakdown(64, 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MembershipPaymentRedisWriteBreakdown breakdown(int batchSize, int lane) {
        return new MembershipPaymentRedisWriteBreakdown(
                1L,
                2L,
                3L,
                4L,
                5L,
                batchSize,
                lane);
    }
}
