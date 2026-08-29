package com.example.temperate.service.user.membership.payment.observability;

/**
 * 该结果是来拆分单条 RabbitMQ 持久消息的本地提交等待与 Broker Confirm 等待，不改变既有发送接口的可靠性语义。
 */
public record MembershipPaymentRabbitPublishBreakdown(
        long submitNanos,
        long confirmWaitNanos,
        int submissionSize) {

    public MembershipPaymentRabbitPublishBreakdown {
        if (submitNanos < 0L || confirmWaitNanos < 0L) {
            throw new IllegalArgumentException("Rabbit publish timing must be non-negative.");
        }
        if (submissionSize != 1) {
            throw new IllegalArgumentException(
                    "Membership payment Rabbit publish submission size must remain one.");
        }
    }
}
