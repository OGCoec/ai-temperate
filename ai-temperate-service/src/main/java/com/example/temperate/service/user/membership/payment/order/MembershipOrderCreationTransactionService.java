package com.example.temperate.service.user.membership.payment.order;

import com.example.temperate.model.user.membership.payment.MembershipOrder;

/**
 * 该事务服务是来仅在 PostgreSQL 内创建或解析 UUIDv4 幂等会员订单，不执行 Redis、RabbitMQ 或外部支付 I/O。
 */
public interface MembershipOrderCreationTransactionService {

    MembershipOrderCreationResult createOrGet(MembershipOrder candidate);
}
