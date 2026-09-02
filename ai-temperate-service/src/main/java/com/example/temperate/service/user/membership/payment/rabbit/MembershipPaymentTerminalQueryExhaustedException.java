package com.example.temperate.service.user.membership.payment.rabbit;

/**
 * 该异常是来指示第三方终态查询已耗尽有限重试，监听器必须按 Quorum Queue 的有限投递规则处理并最终进入 DLQ，且不得篡改既有本地状态。
 */
public final class MembershipPaymentTerminalQueryExhaustedException
        extends RuntimeException {

    public MembershipPaymentTerminalQueryExhaustedException() {
        super("Membership payment terminal query retries are exhausted.");
    }
}
