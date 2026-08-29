package com.example.temperate.service.user.membership.payment.loadtest;

import java.util.List;

/**
 * 该记录是来描述固定 EDU/TEAM 测试夹具是否已准备及恢复快照是否存在，不返回快照中的额度和时间事实。
 */
public record MembershipPaymentRestrictedFixtureState(
        boolean prepared,
        boolean snapshotPresent,
        List<MembershipPaymentRestrictedFixtureUser> users) {

    public MembershipPaymentRestrictedFixtureState {
        users = users == null ? List.of() : List.copyOf(users);
    }
}
