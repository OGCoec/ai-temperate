package com.example.temperate.service.user.aiconversation.billing;

import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;

/**
 * 表示预扣事务采用的强类型依据，禁止使用零 Token 快照伪装供应商成本计量。
 */
public sealed interface AiConversationReservationMetering permits
        TokenReservationMetering,
        ProviderCostReservationMetering,
        VideoProviderCostReservationMetering {

    AiConversationMeteringBasis basis();
}
