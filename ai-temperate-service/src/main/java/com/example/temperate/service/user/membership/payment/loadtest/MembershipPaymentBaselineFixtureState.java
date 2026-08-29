package com.example.temperate.service.user.membership.payment.loadtest;

import java.util.List;

/**
 * 该记录是来向本机 Runner 描述十六个固定账号是否处于统一 FREE 未激活基线，不暴露额度时间或个人资料。
 */
public record MembershipPaymentBaselineFixtureState(
        boolean prepared,
        List<MembershipPaymentBaselineFixtureUser> users) {

    public MembershipPaymentBaselineFixtureState {
        users = users == null ? List.of() : List.copyOf(users);
    }
}
