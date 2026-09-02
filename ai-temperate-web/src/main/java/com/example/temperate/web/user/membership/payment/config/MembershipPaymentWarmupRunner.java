package com.example.temperate.web.user.membership.payment.config;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentWarmupProperties;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.warmup.MembershipPaymentInfrastructureWarmupService;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 该启动器是来在实例进入 readiness 前执行会员支付无副作用技术预热，禁止创建订单、调用 Provider 或发布 Rabbit 消息。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentWarmupRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            MembershipPaymentWarmupRunner.class);

    private final MembershipPaymentWarmupProperties properties;
    private final MembershipPaymentInfrastructureWarmupService warmupService;
    private final RabbitTemplate rabbitTemplate;

    public MembershipPaymentWarmupRunner(
            MembershipPaymentWarmupProperties properties,
            MembershipPaymentInfrastructureWarmupService warmupService,
            @Qualifier("membershipPaymentRabbitTemplate") RabbitTemplate rabbitTemplate) {
        this.properties = Objects.requireNonNull(properties);
        this.warmupService = Objects.requireNonNull(warmupService);
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!properties.enabled()) {
            return;
        }
        try {
            warmupService.warmUpRedisInfrastructure();
            warmUpRabbitInfrastructure();
        } catch (RuntimeException exception) {
            if (properties.failFast()) {
                throw exception;
            }
            LOGGER.warn(
                    "Membership payment infrastructure warmup failed safely; exceptionClass={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void warmUpRabbitInfrastructure() {
        // Passive declare 只验证并预热缓存连接、Channel 与既有拓扑，不产生 Confirm、Return、DLQ 或消费者数据。
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclarePassive(MembershipPaymentRabbitNames.PAYMENT_EXCHANGE);
            channel.exchangeDeclarePassive(MembershipPaymentRabbitNames.CLOSING_EXCHANGE);
            channel.exchangeDeclarePassive(
                    MembershipPaymentRabbitNames.SUPERSEDED_CLOSE_EXCHANGE);
            channel.exchangeDeclarePassive(MembershipPaymentRabbitNames.REFUND_RETRY_EXCHANGE);
            channel.exchangeDeclarePassive(MembershipPaymentRabbitNames.REFUND_TERMINAL_EXCHANGE);
            channel.queueDeclarePassive(MembershipPaymentRabbitNames.PAYMENT_QUEUE);
            channel.queueDeclarePassive(MembershipPaymentRabbitNames.CLOSING_QUEUE);
            channel.queueDeclarePassive(MembershipPaymentRabbitNames.SUPERSEDED_CLOSE_QUEUE);
            channel.queueDeclarePassive(MembershipPaymentRabbitNames.REFUND_RETRY_QUEUE);
            channel.queueDeclarePassive(MembershipPaymentRabbitNames.REFUND_TERMINAL_QUEUE);
            return null;
        });
    }
}
