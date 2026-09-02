package com.example.temperate.service.user.membership.payment.rabbit;

/**
 * 该常量类是来集中定义会员支付、关单与仅超时退款重试的交换机、Quorum 队列、路由键和死信拓扑名称。
 */
public final class MembershipPaymentRabbitNames {

    public static final String PAYMENT_EXCHANGE = "membership.payment.check.delay.exchange";
    public static final String PAYMENT_QUEUE = "membership.payment.check.queue";
    public static final String PAYMENT_ROUTING_KEY = "membership.payment.check";
    public static final String PAYMENT_DLX = "membership.payment.check.dlq.exchange";
    public static final String PAYMENT_DLQ = "membership.payment.check.dlq";
    public static final String PAYMENT_DLQ_ROUTING_KEY = "membership.payment.check.dead";

    public static final String CLOSING_EXCHANGE = "membership.closing.check.delay.exchange";
    public static final String CLOSING_QUEUE = "membership.closing.check.queue";
    public static final String CLOSING_ROUTING_KEY = "membership.closing.check";
    public static final String CLOSING_DLX = "membership.closing.check.dlq.exchange";
    public static final String CLOSING_DLQ = "membership.closing.check.dlq";
    public static final String CLOSING_DLQ_ROUTING_KEY = "membership.closing.check.dead";

    public static final String SUPERSEDED_CLOSE_EXCHANGE =
            "membership.superseded.close.delay.exchange";
    public static final String SUPERSEDED_CLOSE_QUEUE = "membership.superseded.close.queue";
    public static final String SUPERSEDED_CLOSE_ROUTING_KEY = "membership.superseded.close";
    public static final String SUPERSEDED_CLOSE_DLX = "membership.superseded.close.dlq.exchange";
    public static final String SUPERSEDED_CLOSE_DLQ = "membership.superseded.close.dlq";
    public static final String SUPERSEDED_CLOSE_DLQ_ROUTING_KEY =
            "membership.superseded.close.dead";

    public static final String REFUND_RETRY_EXCHANGE =
            "membership.refund.retry.delay.exchange";
    public static final String REFUND_RETRY_QUEUE = "membership.refund.retry.queue";
    public static final String REFUND_RETRY_ROUTING_KEY = "membership.refund.retry";
    public static final String REFUND_RETRY_DLX = "membership.refund.retry.dlq.exchange";
    public static final String REFUND_RETRY_DLQ = "membership.refund.retry.dlq";
    public static final String REFUND_RETRY_DLQ_ROUTING_KEY =
            "membership.refund.retry.dead";

    public static final String REFUND_TERMINAL_EXCHANGE =
            "membership.refund.terminal.exchange";
    public static final String REFUND_TERMINAL_QUEUE = "membership.refund.terminal.queue";
    public static final String REFUND_TERMINAL_ROUTING_KEY = "membership.refund.terminal";

    public static final String PAYMENT_EVENT = "MEMBERSHIP_PAYMENT_CHECK";
    public static final String CLOSING_EVENT = "MEMBERSHIP_CLOSING_CHECK";
    public static final String SUPERSEDED_CLOSE_EVENT = "MEMBERSHIP_SUPERSEDED_CLOSE";
    public static final String REFUND_RETRY_EVENT = "MEMBERSHIP_REFUND_RETRY";
    public static final String REFUND_TERMINAL_EVENT =
            "MEMBERSHIP_REFUND_TERMINAL_FAILURE";
    public static final String LOADTEST_RETRY_EVENT =
            "MEMBERSHIP_PAYMENT_LOADTEST_RETRY";
    public static final String LOADTEST_POISON_EVENT =
            "MEMBERSHIP_PAYMENT_LOADTEST_POISON";

    private MembershipPaymentRabbitNames() {
    }
}
