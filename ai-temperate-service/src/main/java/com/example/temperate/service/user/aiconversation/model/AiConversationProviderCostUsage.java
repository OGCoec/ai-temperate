package com.example.temperate.service.user.aiconversation.model;

/**
 * 表示供应商在单次响应中返回的精确美元成本 ticks，用于不具备 Token 计费证据的协议。
 */
public record AiConversationProviderCostUsage(long costInUsdTicks)
        implements AiConversationMeteredUsage {

    public AiConversationProviderCostUsage {
        if (costInUsdTicks < 0L) {
            throw new IllegalArgumentException(
                    "Provider cost ticks must be non-negative.");
        }
    }

    @Override
    public AiConversationMeteringBasis basis() {
        return AiConversationMeteringBasis.PROVIDER_COST_TICKS;
    }
}
