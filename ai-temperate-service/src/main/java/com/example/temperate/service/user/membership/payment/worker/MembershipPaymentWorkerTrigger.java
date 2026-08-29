package com.example.temperate.service.user.membership.payment.worker;

/**
 * 该触发契约是来合并重复工作信号并启动对应 single-flight drain，定时任务只作为丢失事件的兜底。
 */
public interface MembershipPaymentWorkerTrigger {

    void signal(MembershipPaymentWorkType type);
}
