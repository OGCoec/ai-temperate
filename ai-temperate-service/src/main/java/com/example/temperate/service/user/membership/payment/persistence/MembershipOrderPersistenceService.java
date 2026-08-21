package com.example.temperate.service.user.membership.payment.persistence;

import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import java.util.List;

/**
 * 该事务服务是来使用一次 PostgreSQL 批量更新持久化 Redis 订单快照，只允许更高 stateVersion 前进。
 */
public interface MembershipOrderPersistenceService {

    void persist(List<MembershipOrderSnapshot> snapshots);
}
