package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.util.Objects;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 为四个 Submission Queue 提供薄监听入口，并把所有派发规则委托给统一 Dispatcher 接口。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class MailInspectionSubmissionConsumer {

    private final MailInspectionSubmissionDispatcher dispatcher;

    public MailInspectionSubmissionConsumer(
            MailInspectionSubmissionDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
    }

    @RabbitListener(id = MailInspectionRabbitNames.OPENAI_SUBMISSION_LISTENER_ID,
            queues = MailInspectionRabbitNames.OPENAI_SUBMISSION_QUEUE,
            containerFactory = "adminMailInspectionSubmissionListenerContainerFactory",
            autoStartup = "false")
    public Mono<Void> openAi(MailInspectionSubmissionChunkMessage message) {
        return dispatcher.dispatch(MailInspectionType.OPENAI_STATUS, message);
    }

    @RabbitListener(id = MailInspectionRabbitNames.KIRO_SUBMISSION_LISTENER_ID,
            queues = MailInspectionRabbitNames.KIRO_SUBMISSION_QUEUE,
            containerFactory = "adminMailInspectionSubmissionListenerContainerFactory",
            autoStartup = "false")
    public Mono<Void> kiro(MailInspectionSubmissionChunkMessage message) {
        return dispatcher.dispatch(MailInspectionType.KIRO_STATUS, message);
    }

    @RabbitListener(id = MailInspectionRabbitNames.IP2_REGISTRATION_SUBMISSION_LISTENER_ID,
            queues = MailInspectionRabbitNames.IP2_REGISTRATION_SUBMISSION_QUEUE,
            containerFactory = "adminMailInspectionSubmissionListenerContainerFactory",
            autoStartup = "false")
    public Mono<Void> ip2Registration(MailInspectionSubmissionChunkMessage message) {
        return dispatcher.dispatch(
                MailInspectionType.IP2LOCATION_REGISTRATION, message);
    }

    @RabbitListener(id = MailInspectionRabbitNames.IP2_VERIFY_SUBMISSION_LISTENER_ID,
            queues = MailInspectionRabbitNames.IP2_VERIFY_SUBMISSION_QUEUE,
            containerFactory = "adminMailInspectionSubmissionListenerContainerFactory",
            autoStartup = "false")
    public Mono<Void> ip2Verify(MailInspectionSubmissionChunkMessage message) {
        return dispatcher.dispatch(
                MailInspectionType.IP2LOCATION_VERIFY_LINK, message);
    }
}
