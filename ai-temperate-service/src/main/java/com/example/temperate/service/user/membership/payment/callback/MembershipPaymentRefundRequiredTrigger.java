package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;

/**
 * 该触发器是来记录会员支付需要退款的条件事件；第一版明确不执行退款、不建退款状态机也不调用外部平台。
 */
public interface MembershipPaymentRefundRequiredTrigger {

    void trigger(MembershipOrderStatus sourceStatus);
}
