package com.example.temperate.service.admin.mailinspection.rabbit.impl;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionPublishException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * 统一执行邮箱检查 Rabbit 消息的持久发布、mandatory Return、Publisher Confirm 和有限重试。
 *
 * <p>同步发布和 Confirm 后续处理固定切换到专用 Scheduler，避免 AMQP I/O 回调线程发布下一条消息时等待自身处理 Channel 响应。
 * Work Confirm 完成后上层才可发布 Marker，Marker Confirm 完成后 Submission 才可结束。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class MailInspectionRabbitConfirmedSender {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final AdminMailInspectionProperties properties;
    private final Scheduler publishScheduler;

    public MailInspectionRabbitConfirmedSender(
            @Qualifier("adminMailInspectionRabbitTemplate")
                    RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            AdminMailInspectionProperties properties,
            @Qualifier("adminMailInspectionRabbitPublishScheduler")
                    Scheduler publishScheduler) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.properties = Objects.requireNonNull(properties);
        this.publishScheduler = Objects.requireNonNull(publishScheduler);
    }

    public Mono<Void> send(
            String exchange,
            String routingKey,
            String messageId,
            String eventType,
            Object payload) {
        requireMessageBoundary(payload);
        return sendAttempt(
                exchange,
                routingKey,
                messageId,
                eventType,
                payload,
                1);
    }

    private Mono<Void> sendAttempt(
            String exchange,
            String routingKey,
            String messageId,
            String eventType,
            Object payload,
            int attempt) {
        return Mono.defer(() -> sendOnce(
                        exchange,
                        routingKey,
                        messageId,
                        eventType,
                        payload))
                .onErrorResume(exception -> {
                    if (attempt >= properties.rabbit().publishMaxAttempts()) {
                        return Mono.error(new MailInspectionPublishException(
                                "mail inspection message publish exhausted",
                                exception));
                    }
                    return Mono.delay(backoff(attempt))
                            .then(sendAttempt(
                                    exchange,
                                    routingKey,
                                    messageId,
                                    eventType,
                                    payload,
                                    attempt + 1));
                });
    }

    private Mono<Void> sendOnce(
            String exchange,
            String routingKey,
            String messageId,
            String eventType,
            Object payload) {
        // convertAndSend 可能同步创建 Channel，必须离开 AMQP I/O 回调线程，否则该线程会等待只能由自己读取的 RPC 响应。
        return Mono.fromCallable(() -> publish(
                        exchange,
                        routingKey,
                        messageId,
                        eventType,
                        payload))
                .subscribeOn(publishScheduler)
                .flatMap(correlation ->
                        Mono.fromFuture(correlation.getFuture())
                                .timeout(properties.rabbit().confirmTimeout())
                                // Confirm Future 由 AMQP I/O 线程完成；后续 Marker 发布和状态推进
                                // 必须重新切回隔离 Scheduler。
                                .publishOn(publishScheduler)
                                .flatMap(confirm ->
                                        validateConfirm(correlation, confirm)))
                .then();
    }

    private CorrelationData publish(
            String exchange,
            String routingKey,
            String messageId,
            String eventType,
            Object payload) {
        CorrelationData correlation = new CorrelationData(messageId);
        rabbitTemplate.convertAndSend(
                exchange,
                routingKey,
                payload,
                brokerMessage -> {
                    brokerMessage.getMessageProperties().setMessageId(messageId);
                    brokerMessage.getMessageProperties().setType(eventType);
                    brokerMessage.getMessageProperties().setDeliveryMode(
                            MessageDeliveryMode.PERSISTENT);
                    return brokerMessage;
                },
                correlation);
        return correlation;
    }

    private static Mono<Void> validateConfirm(
            CorrelationData correlation,
            CorrelationData.Confirm confirm) {
        if (!confirm.isAck()) {
            return Mono.error(new MailInspectionPublishException(
                    "mail inspection message was nacked"));
        }
        if (correlation.getReturned() != null) {
            return Mono.error(new MailInspectionPublishException(
                    "mail inspection message was returned"));
        }
        return Mono.empty();
    }

    private void requireMessageBoundary(Object payload) {
        try {
            int bytes = objectMapper.writeValueAsBytes(payload).length;
            if (bytes > properties.submission().maxMessageBytes()) {
                throw new MailInspectionPublishException(
                        "mail inspection Rabbit message exceeds boundary");
            }
        } catch (JsonProcessingException exception) {
            throw new MailInspectionPublishException(
                    "mail inspection Rabbit message serialization failed",
                    exception);
        }
    }

    private Duration backoff(int failedAttempt) {
        List<Duration> values = properties.rabbit().publishBackoffs();
        int index = Math.min(
                Math.max(0, failedAttempt - 1),
                values.size() - 1);
        return values.get(index);
    }
}
