package com.example.temperate.service.user.membership.payment.order;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.util.UUID;

/**
 * 该命令是来承载用户创建会员支付订单的目标等级、服务端白名单支付方式和 UUIDv4 幂等意图。
 */
public record MembershipOrderCreateCommand(
        MembershipTier targetTier,
        String payType,
        UUID idempotencyKey) {
}
