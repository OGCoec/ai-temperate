package com.example.temperate.service.user.membership.payment.store;

/**
 * 该枚举是来区分完整恢复与支付发起增量更新，使一个 Pipeline 可以按输入顺序混合提交两种原子 Lua。
 */
public enum MembershipOrderSnapshotWriteMode {
    FULL_RESTORE,
    PAYMENT_ATTEMPT_PATCH
}
