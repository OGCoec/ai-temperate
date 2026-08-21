package com.example.temperate.service.user.membership.payment.rabbit;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;

/**
 * 该消息是来标识一条 PENDING_PAYMENT 分段检查的订单和阶段，非最终阶段禁止主动查询模拟平台。
 */
public record MembershipPaymentCheckMessage(String orderId, int stageIndex) {

    public MembershipPaymentCheckMessage {
        new MembershipOrderRedisId(orderId);
        if (stageIndex < 0 || stageIndex > 31) {
            throw new IllegalArgumentException("Membership payment stage is invalid.");
        }
    }
}
