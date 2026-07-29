package com.example.temperate.service.admin.mailinspection.rabbit.impl;

import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionChunkMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionPublisher;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 把Submission Chunk可靠发布到检查类型对应的持久Quorum Queue。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class RabbitMailInspectionSubmissionPublisherImpl
        implements MailInspectionSubmissionPublisher {

    private final MailInspectionRabbitConfirmedSender sender;

    public RabbitMailInspectionSubmissionPublisherImpl(
            MailInspectionRabbitConfirmedSender sender) {
        this.sender = Objects.requireNonNull(sender);
    }

    @Override
    public Mono<Void> publish(MailInspectionSubmissionChunkMessage message) {
        return sender.send(
                MailInspectionRabbitNames.SUBMISSION_EXCHANGE,
                MailInspectionRabbitNames.submissionRoutingKey(
                        message.inspectionType()),
                message.messageId(),
                message.eventType(),
                message);
    }
}
