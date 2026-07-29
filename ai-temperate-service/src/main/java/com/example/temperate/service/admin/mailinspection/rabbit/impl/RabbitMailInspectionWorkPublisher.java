package com.example.temperate.service.admin.mailinspection.rabbit.impl;

import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionWorkMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionWorkPublisher;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 使用持久投递、mandatory Return 与 Publisher Confirm 异步发布邮箱检查消息，并执行有限退避重试。
 *
 * <p>实现从不调用 block、join 或 get；调用方只会在 broker 明确 ACK 且消息未退回后收到完成信号。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class RabbitMailInspectionWorkPublisher
        implements MailInspectionWorkPublisher {

    private final MailInspectionRabbitConfirmedSender sender;

    public RabbitMailInspectionWorkPublisher(
            MailInspectionRabbitConfirmedSender sender) {
        this.sender = Objects.requireNonNull(sender);
    }

    @Override
    public Mono<Void> publish(MailInspectionWorkMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        return sender.send(
                MailInspectionRabbitNames.WORK_EXCHANGE,
                MailInspectionRabbitNames.routingKey(
                        message.inspectionType()),
                message.messageId(),
                message.eventType(),
                message);
    }
}
