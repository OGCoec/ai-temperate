package com.example.temperate.service.user.membership.payment.order;

/**
 * 该服务是来编排当前用户会员订单的创建、所有权查询和 Redis 原子取消，不负责发放会员权益或退款。
 */
public interface MembershipOrderService {

    MembershipOrderResult create(
            long loginIdentityId,
            MembershipOrderCreateCommand command);

    MembershipOrderResult getOwned(long loginIdentityId, byte[] orderId);

    MembershipOrderResult cancel(long loginIdentityId, byte[] orderId);
}
