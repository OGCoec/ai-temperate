package com.example.temperate.service.user.membership.payment.store;

/**
 * 该枚举是来表达 Provider 流水号条件补写的原子裁决，防止终态或其他流水号被无版本写入覆盖。
 */
public enum MembershipProviderTradeNoPatchOutcome {
    APPLIED,
    UNCHANGED,
    MISSING,
    CONFLICT
}
