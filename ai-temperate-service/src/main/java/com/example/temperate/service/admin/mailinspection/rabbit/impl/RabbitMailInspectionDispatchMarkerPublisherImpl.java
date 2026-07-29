package com.example.temperate.service.admin.mailinspection.rabbit.impl;

import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerPublisher;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 把无敏感内容的Chunk派发Marker可靠写入固定的持久状态队列。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class RabbitMailInspectionDispatchMarkerPublisherImpl
        implements MailInspectionDispatchMarkerPublisher {

    private final MailInspectionRabbitConfirmedSender sender;

    public RabbitMailInspectionDispatchMarkerPublisherImpl(
            MailInspectionRabbitConfirmedSender sender) {
        this.sender = Objects.requireNonNull(sender);
    }

    @Override
    public Mono<Void> publish(MailInspectionDispatchMarkerMessage message) {
        return sender.send(
                MailInspectionRabbitNames.DISPATCH_STATE_EXCHANGE,
                MailInspectionRabbitNames.dispatchStateRoutingKey(
                        message.inspectionType()),
                message.messageId(),
                message.eventType(),
                message);
    }
}
