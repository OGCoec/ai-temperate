package com.example.temperate.service.user.membership.payment.store;

/**
 * 该枚举是来表达退款终态已由 PostgreSQL 证明后，Redis 缺失快照清理的原子结果。
 */
public enum MembershipPaymentMissingSnapshotReleaseOutcome {
    RELEASED,
    ALREADY_RELEASED,
    CLAIM_MISMATCH,
    CALLBACK_CONFLICT
}
