package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionFailureStage;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.MailInspectionJobState;
import com.example.temperate.service.admin.mailinspection.strategy.MailInspectionStrategy;
import com.example.temperate.service.admin.mailinspection.strategy.MailInspectionStrategyRegistry;
import java.time.Clock;
import java.util.Objects;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 统一消费四类管理员邮箱检查工作消息，并在异步 OAuth、IMAP 与结果保存全部完成后才结束监听器返回的 Mono。
 *
 * <p>Spring AMQP 在 Mono 成功完成后发送 Consumer ACK；本组件不持有 Channel，也不会在异步处理完成前手工确认消息。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class MailInspectionWorkConsumer {

    private final AdminMailInspectionJobStore jobStore;
    private final MailInspectionStrategyRegistry strategyRegistry;
    private final AdminMailInspectionPayloadProtector payloadProtector;
    private final PublicIdCodec publicIdCodec;
    private final AdminMailInspectionProperties properties;
    private final Clock clock;

    public MailInspectionWorkConsumer(
            AdminMailInspectionJobStore jobStore,
            MailInspectionStrategyRegistry strategyRegistry,
            AdminMailInspectionPayloadProtector payloadProtector,
            PublicIdCodec publicIdCodec,
            AdminMailInspectionProperties properties,
            Clock clock) {
        this.jobStore = Objects.requireNonNull(jobStore);
        this.strategyRegistry = Objects.requireNonNull(strategyRegistry);
        this.payloadProtector = Objects.requireNonNull(payloadProtector);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @RabbitListener(
            id = MailInspectionRabbitNames.OPENAI_LISTENER_ID,
            queues = MailInspectionRabbitNames.OPENAI_QUEUE,
            containerFactory = "adminMailInspectionListenerContainerFactory",
            autoStartup = "false")
    public Mono<Void> consumeOpenAi(MailInspectionWorkMessage message) {
        return consume(MailInspectionType.OPENAI_STATUS, message);
    }

    @RabbitListener(
            id = MailInspectionRabbitNames.KIRO_LISTENER_ID,
            queues = MailInspectionRabbitNames.KIRO_QUEUE,
            containerFactory = "adminMailInspectionListenerContainerFactory",
            autoStartup = "false")
    public Mono<Void> consumeKiro(MailInspectionWorkMessage message) {
        return consume(MailInspectionType.KIRO_STATUS, message);
    }

    @RabbitListener(
            id = MailInspectionRabbitNames.IP2_REGISTRATION_LISTENER_ID,
            queues = MailInspectionRabbitNames.IP2_REGISTRATION_QUEUE,
            containerFactory = "adminMailInspectionListenerContainerFactory",
            autoStartup = "false")
    public Mono<Void> consumeIp2Registration(
            MailInspectionWorkMessage message) {
        return consume(
                MailInspectionType.IP2LOCATION_REGISTRATION,
                message);
    }

    @RabbitListener(
            id = MailInspectionRabbitNames.IP2_VERIFY_LISTENER_ID,
            queues = MailInspectionRabbitNames.IP2_VERIFY_QUEUE,
            containerFactory = "adminMailInspectionListenerContainerFactory",
            autoStartup = "false")
    public Mono<Void> consumeIp2Verify(
            MailInspectionWorkMessage message) {
        return consume(
                MailInspectionType.IP2LOCATION_VERIFY_LINK,
                message);
    }

    /**
     * lineNumber 是同一 job 内的幂等键；结果已存在说明上一次业务已完成但 ACK 可能丢失，此次直接成功完成以确认重投递。
     */
    Mono<Void> consume(
            MailInspectionType expectedType,
            MailInspectionWorkMessage message) {
        return Mono.defer(() -> {
                    validateEnvelope(expectedType, message);
                    MailInspectionJobState state = jobStore
                            .find(message.jobInternalId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "mail inspection job state unavailable"));
                    validateState(state, message);
                    if (state.hasResult(message.lineNumber())) {
                        return Mono.empty();
                    }
                    MailInspectionProtectedCredential protectedCredential =
                            payloadProtector.unprotect(
                                    message.messageId(),
                                    message.jobId(),
                                    message.inspectionType(),
                                    message.lineNumber(),
                                    message.protectedPayload());
                    MailboxCredential credential = new MailboxCredential(
                            message.lineNumber(),
                            protectedCredential.email(),
                            protectedCredential.clientId(),
                            protectedCredential.refreshToken());
                    if (!state.itemStarted(message.lineNumber())) {
                        // 另一条投递仍在处理时不能提前 ACK；让 Rabbit 保留这次重复投递，避免原处理因进程中断而造成静默丢失。
                        return Mono.error(new IllegalStateException(
                                "mail inspection line is already in flight"));
                    }
                    MailInspectionStrategy strategy =
                            strategyRegistry.getRequired(expectedType);
                    return strategy.inspect(credential)
                            .switchIfEmpty(Mono.just(
                                    internalFailure(credential)))
                            .onErrorReturn(internalFailure(credential))
                            .doOnNext(result -> {
                                state.recordResult(result);
                                if (state.hasCompletedWork()) {
                                    state.complete(
                                            clock.instant(),
                                            properties.job().retention());
                                }
                            })
                            .then();
                })
                .onErrorMap(
                        this::isPoisonMessage,
                        exception -> new AmqpRejectAndDontRequeueException(
                                "mail inspection poison message rejected",
                                exception));
    }

    private void validateEnvelope(
            MailInspectionType expectedType,
            MailInspectionWorkMessage message) {
        if (message == null
                || !isSupportedSchema(message.schemaVersion())
                || !MailInspectionRabbitNames.EVENT_TYPE.equals(
                        message.eventType())
                || message.inspectionType() != expectedType
                || message.jobInternalId() <= 0
                || !publicIdCodec.encode(message.jobInternalId())
                        .equals(message.jobId())
                || message.lineNumber() < 1
                || message.businessConcurrency() < 1
                || message.businessConcurrency() > 64) {
            throw new MailInspectionPoisonMessageException(
                    "mail inspection message envelope is invalid");
        }
    }

    private static boolean isSupportedSchema(int schemaVersion) {
        return schemaVersion
                        == MailInspectionRabbitNames.LEGACY_WORK_SCHEMA_VERSION
                || schemaVersion
                        == MailInspectionRabbitNames.WORK_SCHEMA_VERSION;
    }

    private static void validateState(
            MailInspectionJobState state,
            MailInspectionWorkMessage message) {
        if (!state.publicId().equals(message.jobId())
                || state.type() != message.inspectionType()
                || state.businessConcurrency()
                        != message.businessConcurrency()
                || (message.schemaVersion()
                                == MailInspectionRabbitNames.WORK_SCHEMA_VERSION
                        && (!Objects.equals(
                                        state.clientRequestId(),
                                        message.clientRequestId())
                                || state.requestFingerprint() == null
                                || !state.requestFingerprint().value().equals(
                                        message.requestFingerprint())
                                || message.sourceChunkIndex() == null
                                || message.sourceChunkIndex() < 0))) {
            throw new MailInspectionPoisonMessageException(
                    "mail inspection message does not match job state");
        }
        // 结果已落入任务状态说明业务处理完成，只是 Consumer ACK 可能丢失；此时必须允许重复投递直接成功结束。
        if (state.hasResult(message.lineNumber())) {
            return;
        }
        if (state.status() != MailInspectionJobStatus.RUNNING) {
            throw new IllegalStateException(
                    "mail inspection consumer started before job approval");
        }
    }

    private boolean isPoisonMessage(Throwable exception) {
        return exception instanceof MailInspectionPayloadException
                || exception
                        instanceof MailInspectionPoisonMessageException;
    }

    private static MailInspectionResult internalFailure(
            MailboxCredential credential) {
        return new MailInspectionResult(
                credential.lineNumber(),
                credential.email(),
                MailInspectionResultStatus.INTERNAL_PROCESSING_FAILURE,
                MailInspectionFailureStage.COORDINATOR,
                "mail_inspection_item_failed",
                0,
                0,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
