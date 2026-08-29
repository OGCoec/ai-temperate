package com.example.temperate.service.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;

/**
 * 该服务是来把主动查询发现的已支付事实归一化到现有 Redis 回调队列，禁止 Rabbit 消费者直接更新订单。
 */
public interface PaymentFactReconciliationService {

    boolean reconcilePaid(
            MembershipOrderSnapshot order,
            PaymentQueryResult paymentFact);
}
