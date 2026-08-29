package com.example.temperate.service.user.membership.payment.loadtest;

/**
 * 该结果是来证明一个固定区段的真实预热事实已被精确删除，同时前序正式订单和回调仍完整保留。
 *
 * @param runScale 固定运行规模
 * @param groupCode 固定区段代码
 * @param warmupRunId 本次预热运行标识
 * @param deletedOrderCount 已删除的预热订单数
 * @param deletedCallbackCount 已删除的预热回调数
 * @param resetQuotaCount 已恢复为 FREE 的当前区段用户数
 * @param currentGroupOrderCount 复位后当前区段订单数
 * @param currentGroupCallbackCount 复位后当前区段回调数
 * @param retainedFormalOrderCount 复位后保留的前序正式订单数
 * @param retainedFormalCallbackCount 复位后保留的前序正式回调数
 */
public record MembershipPaymentSegmentWarmupResetState(
        String runScale,
        String groupCode,
        String warmupRunId,
        int deletedOrderCount,
        int deletedCallbackCount,
        int resetQuotaCount,
        int currentGroupOrderCount,
        int currentGroupCallbackCount,
        int retainedFormalOrderCount,
        int retainedFormalCallbackCount) {
}
