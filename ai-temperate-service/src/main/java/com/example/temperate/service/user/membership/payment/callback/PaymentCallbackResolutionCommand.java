package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackResolution;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该命令是来批量写回支付回调最终裁决，只携带回调 ID、低基数结果和服务端解析时间。
 */
public record PaymentCallbackResolutionCommand(
        String callbackId,
        MembershipPaymentCallbackResolution resolution,
        OffsetDateTime resolvedAt) {

    public PaymentCallbackResolutionCommand {
        new PaymentCallbackRedisId(callbackId);
        resolution = Objects.requireNonNull(resolution, "resolution must not be null");
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
    }
}
