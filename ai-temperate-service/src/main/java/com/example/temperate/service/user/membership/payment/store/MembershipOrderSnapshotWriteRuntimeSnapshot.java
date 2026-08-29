package com.example.temperate.service.user.membership.payment.store;

import java.util.List;

/**
 * 该快照是来只读暴露会员订单 Redis 写入协调器的固定容量与瞬时占用，供本机正式压测验证有界 lane 合同。
 *
 * <p>它不包含订单标识、Redis Key 或写入内容，也不提供任何修改协调器状态的能力。</p>
 */
public record MembershipOrderSnapshotWriteRuntimeSnapshot(
        boolean accepting,
        int configuredBatchSize,
        int configuredLaneCount,
        int maximumInflight,
        int inflight,
        int availablePermits,
        List<Integer> fullRestoreQueueDepths,
        List<Integer> paymentAttemptPatchQueueDepths,
        List<Integer> queueDepths) {

    public MembershipOrderSnapshotWriteRuntimeSnapshot {
        fullRestoreQueueDepths = List.copyOf(fullRestoreQueueDepths);
        paymentAttemptPatchQueueDepths = List.copyOf(paymentAttemptPatchQueueDepths);
        queueDepths = List.copyOf(queueDepths);
    }

    /** 兼容只提供总队列深度的调用方；正式运行快照始终使用包含两类队列的完整构造器。 */
    public MembershipOrderSnapshotWriteRuntimeSnapshot(
            boolean accepting,
            int configuredBatchSize,
            int configuredLaneCount,
            int maximumInflight,
            int inflight,
            int availablePermits,
            List<Integer> queueDepths) {
        this(
                accepting,
                configuredBatchSize,
                configuredLaneCount,
                maximumInflight,
                inflight,
                availablePermits,
                queueDepths,
                java.util.Collections.nCopies(queueDepths.size(), 0),
                queueDepths);
    }
}
