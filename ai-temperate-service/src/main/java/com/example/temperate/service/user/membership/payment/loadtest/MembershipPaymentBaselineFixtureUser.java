package com.example.temperate.service.user.membership.payment.loadtest;

/**
 * 该记录是来返回固定基线账号的非敏感用户 ID 与等级，不携带额度、到期时间或认证信息。
 */
public record MembershipPaymentBaselineFixtureUser(long userId, String tier) {

    public MembershipPaymentBaselineFixtureUser {
        if (userId <= 0L || tier == null || tier.isBlank()) {
            throw new IllegalArgumentException("Baseline fixture user state is invalid.");
        }
    }
}
