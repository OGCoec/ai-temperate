package com.example.temperate.service.user.membership.payment.rabbit;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;

/**
 * 该消息是来携带被替换旧订单的第三方关单重试次数，本地订单在发布前已经处于不可发权益的终态。
 */
public record MembershipSupersededCloseMessage(
        String orderId,
        int retryCount) {

    public MembershipSupersededCloseMessage {
        new MembershipOrderRedisId(orderId);
        if (retryCount < 0 || retryCount > 10) {
            throw new IllegalArgumentException("Superseded close retry count is invalid.");
        }
    }
}
