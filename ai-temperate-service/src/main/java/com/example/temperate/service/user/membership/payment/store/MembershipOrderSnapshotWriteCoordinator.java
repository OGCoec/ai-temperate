package com.example.temperate.service.user.membership.payment.store;

import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;

/**
 * 该协调器是来把并发 HTTP 的单订单快照写入收敛成有界 Pipeline，同时保持每个订单独立 Lua 的原子裁决边界。
 */
public interface MembershipOrderSnapshotWriteCoordinator {

    MembershipOrderSnapshot putAndGet(MembershipOrderSnapshot snapshot);

    MembershipOrderSnapshot patchPaymentAttempt(
            MembershipOrderSnapshot databaseSnapshot);

    /** 返回不触发 Redis I/O 的瞬时容量快照，供本机负载测试验证配置和有界在途不变量。 */
    MembershipOrderSnapshotWriteRuntimeSnapshot runtimeSnapshot();
}
