package com.example.temperate.service.registration.verification.delivery.rabbit;

/**
 * 集中保存验证码投递 RabbitMQ 拓扑名称和载荷版本，避免生产者、消费者和 Web 配置使用不一致的契约。
 *
 * <p>既有 v1 拓扑名称保持不变以避免重建队列；消息载荷独立升级为 schema v2。</p>
 */
public final class VerificationDeliveryRabbitNames {

    public static final String EXCHANGE = "ait.auth.verification-delivery.delay.v1";
    public static final String EMAIL_QUEUE = "ait.auth.verification-delivery.email.v1";
    public static final String SMS_QUEUE = "ait.auth.verification-delivery.sms.v1";
    public static final String TERMINAL_EXCHANGE =
            "ait.auth.verification-delivery.terminal.v1";
    public static final String TERMINAL_QUEUE =
            "ait.auth.verification-delivery.terminal.v1";
    public static final String EMAIL_ROUTING_KEY = "verification.email";
    public static final String SMS_ROUTING_KEY = "verification.sms";
    public static final String TERMINAL_ROUTING_KEY = "verification.terminal";
    public static final String EVENT_TYPE = "auth.verification.delivery.requested";
    public static final int SCHEMA_VERSION = 2;
    public static final String TERMINAL_EVENT_TYPE =
            "auth.verification.delivery.failed";
    public static final int TERMINAL_SCHEMA_VERSION = 2;

    private VerificationDeliveryRabbitNames() {
    }
}
