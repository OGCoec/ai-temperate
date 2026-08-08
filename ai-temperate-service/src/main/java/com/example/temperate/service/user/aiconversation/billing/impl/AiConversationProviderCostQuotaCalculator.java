package com.example.temperate.service.user.aiconversation.billing.impl;

import org.springframework.stereotype.Component;

/**
 * 将供应商美元成本 ticks 安全换算为项目额度，并为 xAI 图片输出槽计算固定预扣。
 */
@Component
public final class AiConversationProviderCostQuotaCalculator {

    /** xAI 成本 ticks 与账户 0.01 美元最小单位之间的换算比例。 */
    public static final long COST_TICKS_PER_ACCOUNT_MINOR = 100_000_000L;
    /**
     * 保留旧常量名，避免其他模块在升级期间发生源码兼容性断裂。
     *
     * @deprecated 请使用 {@link #COST_TICKS_PER_ACCOUNT_MINOR}。
     */
    @Deprecated
    public static final long COST_TICKS_PER_QUOTA = COST_TICKS_PER_ACCOUNT_MINOR;
    /** 图片预扣采用的保守额度：每张图片预扣 1.00 美元，即 100 个最小单位。 */
    public static final long RESERVED_MINOR_UNITS_PER_OUTPUT = 100L;
    /**
     * 保留旧常量名；它只用于图片输出数量预扣，不参与 ticks 实际成本换算。
     *
     * @deprecated 请使用 {@link #RESERVED_MINOR_UNITS_PER_OUTPUT}。
     */
    @Deprecated
    public static final long MINOR_UNITS_PER_QUOTA = RESERVED_MINOR_UNITS_PER_OUTPUT;
    public static final long RESERVED_QUOTA_PER_OUTPUT = 1L;

    /**
     * 将供应商实际成本从 USD ticks 向上换算为账户最小额度单位。
     * 结果可直接用于余额扣减，不能再次乘以 100。
     *
     * @param costInUsdTicks 供应商返回的非负 USD ticks
     * @return 以 0.01 美元为一个单位的整数额度
     */
    public long actualQuotaMinor(long costInUsdTicks) {
        if (costInUsdTicks < 0L) {
            throw new IllegalArgumentException(
                    "Provider cost ticks must be non-negative.");
        }
        long quotient = costInUsdTicks / COST_TICKS_PER_ACCOUNT_MINOR;
        long remainder = costInUsdTicks % COST_TICKS_PER_ACCOUNT_MINOR;
        long accountMinor = remainder == 0L
                ? quotient
                : Math.addExact(quotient, 1L);
        // 数据库保存的是两位小数的最小单位，因此向上取整后直接返回；再次乘 100 会造成一百倍超扣。
        return accountMinor;
    }

    /**
     * 按图片输出数量执行保守预扣；该策略不读取供应商实际 ticks。
     *
     * @param requestedOutputCount 请求的图片数量
     * @return 预扣的账户最小额度单位
     */
    public long reservedQuotaMinor(short requestedOutputCount) {
        if (requestedOutputCount < 1 || requestedOutputCount > 10) {
            throw new IllegalArgumentException(
                    "Requested image output count is out of range.");
        }
        long quota = Math.multiplyExact(
                RESERVED_QUOTA_PER_OUTPUT, requestedOutputCount);
        return Math.multiplyExact(quota, RESERVED_MINOR_UNITS_PER_OUTPUT);
    }

    /**
     * 按预估供应商成本计算视频预扣，和最终结算共用同一整数换算。
     *
     * @param estimatedProviderCostTicks 预估的供应商成本 ticks
     * @return 预扣的账户最小额度单位
     */
    public long reservedQuotaMinor(long estimatedProviderCostTicks) {
        return actualQuotaMinor(estimatedProviderCostTicks);
    }
}
