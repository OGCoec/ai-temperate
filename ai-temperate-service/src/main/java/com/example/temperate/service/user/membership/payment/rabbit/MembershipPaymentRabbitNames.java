package com.example.temperate.service.user.membership.payment.rabbit;

/**
 * 该常量类是来集中定义会员支付与软关闭检查的延时交换机、Quorum 队列、路由键和死信拓扑名称。
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

    public static final String PAYMENT_EVENT = "MEMBERSHIP_PAYMENT_CHECK";
    public static final String CLOSING_EVENT = "MEMBERSHIP_CLOSING_CHECK";
    public static final String LOADTEST_RETRY_EVENT =
            "MEMBERSHIP_PAYMENT_LOADTEST_RETRY";
    public static final String LOADTEST_POISON_EVENT =
            "MEMBERSHIP_PAYMENT_LOADTEST_POISON";

    private MembershipPaymentRabbitNames() {
    }
}
