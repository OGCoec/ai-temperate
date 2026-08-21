package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.common.redis.key.PaymentCallbackRedisId;

/**
 * 该结果是来表达模拟六号回调已首次进入 ready 队列或命中三十秒 Redis 短期重复，两者都应向支付方确认成功。
 */
public record SimulatedLiuhaoCallbackResult(
        String callbackId,
        boolean duplicate) {

    public SimulatedLiuhaoCallbackResult {
        new PaymentCallbackRedisId(callbackId);
    }
}
