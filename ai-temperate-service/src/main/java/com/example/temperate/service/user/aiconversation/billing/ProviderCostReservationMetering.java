package com.example.temperate.service.user.aiconversation.billing;

import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;

/**
 * 冻结供应商成本请求的输出槽数量，xAI 图片由该数量计算固定预扣而不读取 Token 倍率。
 */
public record ProviderCostReservationMetering(short requestedOutputCount)
        implements AiConversationReservationMetering {

    public ProviderCostReservationMetering {
        if (requestedOutputCount < 1 || requestedOutputCount > 10) {
            throw new IllegalArgumentException(
                    "Requested image output count is out of range.");
        }
    }

    @Override
    public AiConversationMeteringBasis basis() {
        return AiConversationMeteringBasis.PROVIDER_COST_TICKS;
    }
}
