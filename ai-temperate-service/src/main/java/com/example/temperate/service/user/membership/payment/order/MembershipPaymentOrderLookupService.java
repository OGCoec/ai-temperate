package com.example.temperate.service.user.membership.payment.order;

import java.util.Optional;

/**
 * 该服务是来为后台检查按 Redis 优先规则读取无用户上下文的订单状态，并在缓存缺失时从 PostgreSQL 恢复非终态快照。
 */
public interface MembershipPaymentOrderLookupService {

    Optional<MembershipOrderSnapshot> find(String orderId);
}
