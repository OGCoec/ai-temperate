package com.example.temperate.service.user.membership.payment.observability;

/**
 * 该明细是来拆分一次协调写入的非重叠等待区间，使绿字可以区分削峰、排队、组批、执行和结果分发。
 */
public record MembershipPaymentRedisWriteBreakdown(
        long permitWaitNanos,
        long queueWaitNanos,
        long batchWaitNanos,
        long executionNanos,
        long dispatchNanos,
        int batchSize,
        int lane) {

    public MembershipPaymentRedisWriteBreakdown {
        permitWaitNanos = Math.max(0L, permitWaitNanos);
        queueWaitNanos = Math.max(0L, queueWaitNanos);
        batchWaitNanos = Math.max(0L, batchWaitNanos);
        executionNanos = Math.max(0L, executionNanos);
        dispatchNanos = Math.max(0L, dispatchNanos);
        if (batchSize < 1 || batchSize > 64 || lane < 0 || lane > 5) {
            throw new IllegalArgumentException("Membership payment Redis write breakdown is invalid.");
        }
    }
}
