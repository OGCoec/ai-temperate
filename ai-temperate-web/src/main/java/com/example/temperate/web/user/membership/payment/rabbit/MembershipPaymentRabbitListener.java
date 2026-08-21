package com.example.temperate.web.user.membership.payment.rabbit;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckConsumerService;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 该监听器是来把两条会员支付 Quorum 队列交给业务服务，并且只在业务处理及下一条消息 Confirm 成功后手动 ACK。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentRabbitListener {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MembershipPaymentRabbitListener.class);

    private final MembershipPaymentCheckConsumerService paymentService;
    private final MembershipClosingCheckConsumerService closingService;
    private final MembershipPaymentMetrics metrics;
    private final MembershipPaymentLoadtestProperties loadtestProperties;

    public MembershipPaymentRabbitListener(
            MembershipPaymentCheckConsumerService paymentService,
            MembershipClosingCheckConsumerService closingService,
            MembershipPaymentMetrics metrics) {
        this(
                paymentService,
                closingService,
                metrics,
                new MembershipPaymentLoadtestProperties(false, java.util.List.of()));
    }

    @Autowired
    public MembershipPaymentRabbitListener(
            MembershipPaymentCheckConsumerService paymentService,
            MembershipClosingCheckConsumerService closingService,
            MembershipPaymentMetrics metrics,
            MembershipPaymentLoadtestProperties loadtestProperties) {
        this.paymentService = Objects.requireNonNull(paymentService);
        this.closingService = Objects.requireNonNull(closingService);
        this.metrics = Objects.requireNonNull(metrics);
        this.loadtestProperties = Objects.requireNonNull(loadtestProperties);
    }

    @RabbitListener(
            queues = MembershipPaymentRabbitNames.PAYMENT_QUEUE,
            containerFactory = "membershipPaymentListenerContainerFactory")
    public void consumePayment(
            MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage> envelope,
            Message message,
            Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Runnable restoreLoggingContext = installLoggingContext(envelope);
        try {
            if (!handleLoadtestProbe(envelope, message)) {
                paymentService.process(envelope);
            }
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException exception) {
            reject(channel, deliveryTag, envelope, message, exception);
        } finally {
            restoreLoggingContext.run();
        }
    }

    @RabbitListener(
            queues = MembershipPaymentRabbitNames.CLOSING_QUEUE,
            containerFactory = "membershipPaymentListenerContainerFactory")
    public void consumeClosing(
            MembershipPaymentRabbitEnvelope<MembershipClosingCheckMessage> envelope,
            Message message,
            Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Runnable restoreLoggingContext = installLoggingContext(envelope);
        try {
            closingService.process(envelope);
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException exception) {
            reject(channel, deliveryTag, envelope, message, exception);
        } finally {
            restoreLoggingContext.run();
        }
    }

    private void reject(
            Channel channel,
            long deliveryTag,
            MembershipPaymentRabbitEnvelope<?> envelope,
            Message message,
            RuntimeException exception) throws IOException {
        long priorDeliveries = deliveryCount(message);
        LOGGER.warn(
                "Membership payment Rabbit message failed and will follow the finite quorum delivery limit; "
                        + "traceId={} messageId={} priorDeliveries={} reason={}",
                envelope == null ? "unavailable" : envelope.traceId(),
                envelope == null ? "unavailable" : envelope.messageId(),
                priorDeliveries,
                exception.getClass().getSimpleName());
        if (priorDeliveries >= 2L) {
            metrics.dlq();
        }
        // 业务队列固定为 x-delivery-limit=3 的 Quorum Queue；requeue 只允许有限重投，耗尽后由 RabbitMQ 进入 DLQ。
        channel.basicNack(deliveryTag, false, true);
    }

    private boolean handleLoadtestProbe(
            MembershipPaymentRabbitEnvelope<?> envelope,
            Message message) {
        if (!loadtestProperties.enabled() || envelope == null) {
            return false;
        }
        if (MembershipPaymentRabbitNames.LOADTEST_POISON_EVENT.equals(
                envelope.eventType())) {
            throw new IllegalStateException("Loadtest Rabbit poison probe.");
        }
        if (!MembershipPaymentRabbitNames.LOADTEST_RETRY_EVENT.equals(
                envelope.eventType())) {
            return false;
        }
        // x-delivery-count 从零开始；前两次受控失败，第三次由监听器手动 ACK，证明有限重投可以恢复成功。
        if (deliveryCount(message) < 2L) {
            throw new IllegalStateException("Loadtest Rabbit transient probe.");
        }
        return true;
    }

    private static long deliveryCount(Message message) {
        if (message == null) {
            return 0L;
        }
        Object value = message.getMessageProperties().getHeader("x-delivery-count");
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private static Runnable installLoggingContext(
            MembershipPaymentRabbitEnvelope<?> envelope) {
        String previousTraceId = MDC.get("traceId");
        String previousMessageId = MDC.get("messageId");
        if (envelope != null) {
            // Rabbit 线程会复用；处理期间传播信封上下文，结束后恢复原值，既延续下一阶段 Trace 又避免串单。
            MDC.put("traceId", envelope.traceId());
            MDC.put("messageId", envelope.messageId());
        }
        return () -> {
            restoreMdc("traceId", previousTraceId);
            restoreMdc("messageId", previousMessageId);
        };
    }

    private static void restoreMdc(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }
}
