package com.example.temperate.service.user.membership.payment.store;

/**
 * 该枚举是来统一解析完整恢复与增量支付脚本的固定返回结果，并限制协调器可接受的状态集合。
 */
public enum MembershipOrderSnapshotWriteOutcome {
    CREATED,
    REPLACED,
    APPLIED,
    UNCHANGED,
    STALE,
    MISSING,
    REQUIRES_RESTORE,
    CONFLICT
}
