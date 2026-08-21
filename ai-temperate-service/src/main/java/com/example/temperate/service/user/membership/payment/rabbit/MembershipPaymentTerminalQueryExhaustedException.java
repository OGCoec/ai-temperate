package com.example.temperate.service.user.membership.payment.rabbit;

/**
 * 该异常是来指示 CLOSING 最终查询已耗尽有限重试，监听器必须拒绝且不重新入队，使消息进入 DLQ 并保留订单 CLOSING。
 */
public final class MembershipPaymentTerminalQueryExhaustedException
        extends RuntimeException {

    public MembershipPaymentTerminalQueryExhaustedException() {
        super("Membership payment terminal query retries are exhausted.");
    }
}
