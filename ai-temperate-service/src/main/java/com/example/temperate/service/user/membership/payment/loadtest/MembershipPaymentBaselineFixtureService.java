package com.example.temperate.service.user.membership.payment.loadtest;

/**
 * 该服务是来把十六个固定压测账号事务化恢复为统一 FREE 基线，不接受任意用户、等级或额度输入。
 */
public interface MembershipPaymentBaselineFixtureService {

    MembershipPaymentBaselineFixtureState prepare();

    MembershipPaymentBaselineFixtureState state();
}
