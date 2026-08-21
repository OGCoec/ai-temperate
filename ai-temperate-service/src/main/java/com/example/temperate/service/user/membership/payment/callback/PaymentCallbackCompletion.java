package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;
import java.util.Objects;

/**
 * 该完成令牌是来把回调领取代次与受控订单标识一起交给清理 Lua，使 marker Key 只能由 KeyFactory 生成。
 *
 * <p>回调 Hash 已缺失时订单标识允许为空，此时只精确清理 processing，未知 marker 由固定 TTL 收敛。
 * 数据库判定新通知撞上既有唯一事实时删除临时结果；业务校验拒绝成功通知时则恢复明确 UNPAID，
 * 使后续软关单不会把“无效成功”误当成 UNKNOWN 并耗尽重试。</p>
 */
public record PaymentCallbackCompletion(
        PaymentCallbackClaim claim,
        String orderId,
        PaymentProviderResultCompletionAction providerResultAction) {

    public PaymentCallbackCompletion(
            PaymentCallbackClaim claim,
            String orderId) {
        this(claim, orderId, PaymentProviderResultCompletionAction.KEEP);
    }

    public PaymentCallbackCompletion {
        claim = Objects.requireNonNull(claim, "claim must not be null");
        providerResultAction = Objects.requireNonNull(
                providerResultAction, "providerResultAction must not be null");
        if (orderId != null) {
            orderId = new MembershipOrderRedisId(orderId).value();
        }
    }
}
