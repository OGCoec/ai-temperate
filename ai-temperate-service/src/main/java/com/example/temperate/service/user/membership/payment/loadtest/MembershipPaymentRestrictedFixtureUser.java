package com.example.temperate.service.user.membership.payment.loadtest;

/**
 * 该记录是来向本机 Runner 暴露固定受限套餐账号的非敏感当前等级，不包含额度明细或原始恢复快照。
 */
public record MembershipPaymentRestrictedFixtureUser(long userId, String tier) {

    public MembershipPaymentRestrictedFixtureUser {
        if (userId <= 0L || tier == null || tier.isBlank()) {
            throw new IllegalArgumentException("Restricted fixture user state is invalid.");
        }
    }
}
