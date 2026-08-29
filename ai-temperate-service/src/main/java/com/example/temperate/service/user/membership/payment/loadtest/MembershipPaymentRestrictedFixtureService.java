package com.example.temperate.service.user.membership.payment.loadtest;

/**
 * 该服务是来事务化准备、查看和恢复四个固定 EDU/TEAM 压测账号，不接受任意用户或套餐输入。
 */
public interface MembershipPaymentRestrictedFixtureService {

    MembershipPaymentRestrictedFixtureState prepare();

    MembershipPaymentRestrictedFixtureState state();

    MembershipPaymentRestrictedFixtureState restore();
}
