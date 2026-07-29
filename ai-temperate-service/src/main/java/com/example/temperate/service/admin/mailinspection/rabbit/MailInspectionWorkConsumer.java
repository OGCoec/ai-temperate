package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionFailureStage;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionJobKeyHasher;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionRedisJobDocument;
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
 * 消费 Rabbit v2 邮件工作消息，并在 Redis 结果原子写入成功后才允许监听 Mono 完成。
 *
 * <p>同一行由 Redis claim 去重；已有结果的 Rabbit 重投递直接完成，仍在处理的重复投递则失败并等待后续重试。</p>
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
    private final HybridBase64UrlCodec jobIdCodec;
    private final MailInspectionJobKeyHasher keyHasher;
    private final MailInspectionListenerControl listenerControl;
    private final Clock clock;

    public MailInspectionWorkConsumer(
            AdminMailInspectionJobStore jobStore,
            MailInspectionStrategyRegistry strategyRegistry,
            AdminMailInspectionPayloadProtector payloadProtector,
            HybridBase64UrlCodec jobIdCodec,
            MailInspectionJobKeyHasher keyHasher,
            MailInspectionListenerControl listenerControl,
            Clock clock) {
        this.jobStore = Objects.requireNonNull(jobStore);
        this.strategyRegistry = Objects.requireNonNull(strategyRegistry);
        this.payloadProtector = Objects.requireNonNull(payloadProtector);
        this.jobIdCodec = Objects.requireNonNull(jobIdCodec);
        this.keyHasher = Objects.requireNonNull(keyHasher);
        this.listenerControl = Objects.requireNonNull(listenerControl);
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

    Mono<Void> consume(
            MailInspectionType expectedType,
            MailInspectionWorkMessage message) {
        return Mono.defer(() -> {
                    validateEnvelope(expectedType, message);
                    MailInspectionRedisJobDocument document =
                            jobStore.findSnapshotMeta(message.jobId())
                                    .orElseThrow(() ->
                                            new AdminException(
                                                    AdminErrorCode
                                                            .ADMIN_MAIL_INSPECTION_UNAVAILABLE,
                                                    "mail inspection Redis job unavailable"));
                    validateDocument(document, message);
                    if (jobStore.hasResult(
                            message.jobId(), message.lineNumber())) {
                        return Mono.empty();
                    }
                    MailInspectionProtectedCredential protectedCredential =
                            payloadProtector.unprotect(
                                    message.messageId(),
                                    message.jobId(),
                                    message.jobKeyHash(),
                                    message.inspectionType(),
                                    message.lineNumber(),
                                    message.protectedPayload());
                    MailboxCredential credential = new MailboxCredential(
                            message.lineNumber(),
                            protectedCredential.email(),
                            protectedCredential.clientId(),
                            protectedCredential.refreshToken());
                    if (!jobStore.claimLine(
                            message.jobId(),
                            message.lineNumber(),
                            clock.instant())) {
                        if (jobStore.hasResult(
                                message.jobId(), message.lineNumber())) {
                            return Mono.empty();
                        }
                        return Mono.error(new IllegalStateException(
                                "mail inspection line is already in flight"));
                    }
                    MailInspectionStrategy strategy =
                            strategyRegistry.getRequired(expectedType);
                    return strategy.inspect(credential)
                            .switchIfEmpty(Mono.just(
                                    internalFailure(credential)))
                            .onErrorReturn(internalFailure(credential))
                            .flatMap(result -> Mono.fromRunnable(() ->
                                    jobStore.recordResult(
                                            message.jobId(),
                                            result,
                                            clock.instant())))
                            .then();
                })
                .doOnError(exception ->
                        pauseOnRedisFailure(expectedType, exception))
                .onErrorMap(
                        this::isPoisonMessage,
                        exception -> new AmqpRejectAndDontRequeueException(
                                "mail inspection poison message rejected",
                                exception));
    }

    private void validateEnvelope(
            MailInspectionType expectedType,
            MailInspectionWorkMessage message) {
        try {
            if (message == null
                    || message.schemaVersion()
                    != MailInspectionRabbitNames.WORK_SCHEMA_VERSION
                    || !MailInspectionRabbitNames.EVENT_TYPE.equals(
                            message.eventType())
                    || message.inspectionType() != expectedType
                    || jobIdCodec.decode(message.jobId()).length
                    != HybridBase64UrlCodec.BINARY_LENGTH
                    || !keyHasher.hashJobId(message.jobId()).value()
                            .equals(message.jobKeyHash())
                    || message.lineNumber() < 1
                    || message.businessConcurrency() < 1
                    || message.businessConcurrency() > 64) {
                throw new MailInspectionPoisonMessageException(
                        "mail inspection message envelope is invalid");
            }
        } catch (IllegalArgumentException exception) {
            throw new MailInspectionPoisonMessageException(
                    "mail inspection message envelope is invalid",
                    exception);
        }
    }

    private static void validateDocument(
            MailInspectionRedisJobDocument document,
            MailInspectionWorkMessage message) {
        // Redis 文档是唯一事实来源，消息中的计数、分块和身份字段必须逐项一致后才能解密并执行邮箱业务。
        if (!document.jobId().equals(message.jobId())
                || !document.jobHash().equals(message.jobKeyHash())
                || document.inspectionType() != message.inspectionType()
                || document.requestedCount() != message.requestedCount()
                || document.acceptedCount() != message.acceptedCount()
                || document.duplicateCount() != message.duplicateCount()
                || document.invalidCount() != message.invalidCount()
                || document.businessConcurrency()
                != message.businessConcurrency()
                || !Objects.equals(
                        document.clientRequestId(),
                        message.clientRequestId())
                || !Objects.equals(
                        document.requestFingerprint(),
                        message.requestFingerprint())
                || message.sourceChunkIndex() == null
                || message.sourceChunkIndex() < 0
                || message.sourceChunkIndex()
                >= document.submissionChunkCount()
                || message.lineNumber() > document.requestedCount()) {
            throw new MailInspectionPoisonMessageException(
                    "mail inspection message does not match Redis job");
        }
        if (document.status() != MailInspectionJobStatus.RUNNING) {
            throw new IllegalStateException(
                    "mail inspection consumer started before job approval");
        }
    }

    private boolean isPoisonMessage(Throwable exception) {
        return exception instanceof MailInspectionPayloadException
                || exception instanceof MailInspectionPoisonMessageException;
    }

    private void pauseOnRedisFailure(
            MailInspectionType type, Throwable failure) {
        if (!isRedisAuthorityFailure(failure)) {
            return;
        }
        // 停止监听器可能等待当前消费线程退出，因此交给独立调度线程执行，当前消息保持未 ACK 并回到 Ready。
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> {
            try {
                listenerControl.stop(type);
            } catch (RuntimeException ignored) {
                // 控制面停止失败不能掩盖原始 Redis 故障，启动恢复检查会继续保持接收闸门关闭。
            }
        });
    }

    private static boolean isRedisAuthorityFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AdminException adminException
                    && (adminException.code()
                    == AdminErrorCode.ADMIN_MAIL_INSPECTION_UNAVAILABLE
                    || adminException.code()
                    == AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
