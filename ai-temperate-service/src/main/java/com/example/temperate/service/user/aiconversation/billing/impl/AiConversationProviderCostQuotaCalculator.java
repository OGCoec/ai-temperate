package com.example.temperate.service.user.aiconversation.billing.impl;

import org.springframework.stereotype.Component;

/**
 * 将供应商美元成本 ticks 安全换算为项目额度，并为 xAI 图片输出槽计算固定预扣。
 */
@Component
public final class AiConversationProviderCostQuotaCalculator {

    public static final long COST_TICKS_PER_QUOTA = 100_000_000L;
    public static final long MINOR_UNITS_PER_QUOTA = 100L;
    public static final long RESERVED_QUOTA_PER_OUTPUT = 1L;

    public long actualQuotaMinor(long costInUsdTicks) {
        if (costInUsdTicks < 0L) {
            throw new IllegalArgumentException(
                    "Provider cost ticks must be non-negative.");
        }
        long quotient = costInUsdTicks / COST_TICKS_PER_QUOTA;
        long remainder = costInUsdTicks % COST_TICKS_PER_QUOTA;
        long quota = remainder == 0L
                ? quotient
                : Math.addExact(quotient, 1L);
        // 数据库存储的是两位小数的 quota_minor；先完成 ticks 除法再缩放，避免先乘造成溢出。
        return Math.multiplyExact(quota, MINOR_UNITS_PER_QUOTA);
    }

    public long reservedQuotaMinor(short requestedOutputCount) {
        if (requestedOutputCount < 1 || requestedOutputCount > 10) {
            throw new IllegalArgumentException(
                    "Requested image output count is out of range.");
        }
        long quota = Math.multiplyExact(
                RESERVED_QUOTA_PER_OUTPUT, requestedOutputCount);
        return Math.multiplyExact(quota, MINOR_UNITS_PER_QUOTA);
    }
}
