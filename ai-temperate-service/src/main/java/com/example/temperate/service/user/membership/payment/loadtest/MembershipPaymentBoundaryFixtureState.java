package com.example.temperate.service.user.membership.payment.loadtest;

/**
 * 汇总持久毫秒边界测试夹具的非敏感数量和可运行状态，不暴露邮箱、Token 或订单标识。
 *
 * @param prepared 模板完整、额度为 FREE 未激活基线且不存在订单或回调残留
 * @param identityCount 固定区间身份数量
 * @param profileCount 固定区间资料数量
 * @param quotaCount 固定区间额度数量
 * @param orderCount 固定区间订单数量
 * @param callbackCount 固定区间回调数量
 */
public record MembershipPaymentBoundaryFixtureState(
        boolean prepared,
        int identityCount,
        int profileCount,
        int quotaCount,
        int orderCount,
        int callbackCount) {
}
