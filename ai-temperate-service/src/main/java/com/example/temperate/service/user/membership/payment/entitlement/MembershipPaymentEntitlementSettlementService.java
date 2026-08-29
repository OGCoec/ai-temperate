package com.example.temperate.service.user.membership.payment.entitlement;

import java.util.List;

/**
 * 该服务是来在 PostgreSQL 本地事务中统一提交会员订单状态、权益裁决、用户套餐额度与回调裁决。
 *
 * <p>成功发放与退款裁决使用独立公开方法，外部 BAR 退款必须在退款裁决事务提交后执行。</p>
 */
public interface MembershipPaymentEntitlementSettlementService {

    void settleApplied(List<MembershipPaymentEntitlementCommand> commands);

    void settleRefundRequired(
            List<MembershipPaymentRefundEntitlementCommand> commands);
}
