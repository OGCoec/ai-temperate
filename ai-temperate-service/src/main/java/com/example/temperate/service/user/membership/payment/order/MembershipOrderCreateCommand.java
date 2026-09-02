package com.example.temperate.service.user.membership.payment.order;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import java.util.UUID;

/**
 * 该命令是来承载用户创建会员支付订单的目标等级、支付方式和 UUIDv4 幂等意图；旧 Provider 字段仅兼容接收且不参与订单身份。
 */
public record MembershipOrderCreateCommand(
        MembershipTier targetTier,
        String payType,
        UUID idempotencyKey,
        PaymentProviderType provider) {

    /** 该兼容构造器是来支持已移除旧 provider 字段的新调用方。 */
    public MembershipOrderCreateCommand(
            MembershipTier targetTier,
            String payType,
            UUID idempotencyKey) {
        this(targetTier, payType, idempotencyKey, null);
    }
}
