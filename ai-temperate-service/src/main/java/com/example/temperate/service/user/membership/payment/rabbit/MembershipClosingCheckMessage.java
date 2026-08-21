package com.example.temperate.service.user.membership.payment.rabbit;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;

/**
 * 该消息是来标识一条 CLOSING 分段检查及最终 UNKNOWN 重试次数，重试耗尽后必须进入死信而不能关闭订单。
 */
public record MembershipClosingCheckMessage(
        String orderId,
        int stageIndex,
        int terminalRetryCount) {

    public MembershipClosingCheckMessage {
        new MembershipOrderRedisId(orderId);
        if (stageIndex < 0 || stageIndex > 31
                || terminalRetryCount < 0 || terminalRetryCount > 10) {
            throw new IllegalArgumentException("Membership closing stage is invalid.");
        }
    }
}
