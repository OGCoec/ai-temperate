package com.example.temperate.service.user.membership.payment.worker;

/**
 * 该枚举是来约束一次 Callback 或 Dirty Worker 的退出原因，触发器只对 CAPACITY 执行有界连续续跑。
 */
public enum MembershipPaymentWorkerOutcome {
    DRAINED,
    CAPACITY,
    RETRY,
    LOCK_UNAVAILABLE,
    PAUSED,
    FAILED
}
