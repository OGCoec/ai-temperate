package com.example.temperate.service.user.aiconversation.generation.recovery.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationPayloadMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementService;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationObserverStatus;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalCommand;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalType;
import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationService;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationEventPublisher;
import com.example.temperate.service.user.aiconversation.generation.recovery.AiConversationGenerationRecoveryService;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationStage;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 复用既有分钟级运维节拍补偿 MQ 发布空窗、失联检查、超时 Owner 与终态清理，并在结算耗尽后进入人工核对。
 * 实时取消和正常计费不依赖本服务，所有查询都受批量上限约束。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationRecoveryServiceImpl
        implements AiConversationGenerationRecoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AiConversationGenerationRecoveryServiceImpl.class);

    private final AiConversationGenerationMapper generationMapper;
    private final AiConversationSettlementService settlementService;
    private final AiConversationGenerationPayloadMapper payloadMapper;
    private final AiConversationGenerationCancellationService cancellationService;
    private final AiConversationGenerationTerminalService terminalService;
    private final AiConversationGenerationEventPublisher eventPublisher;
    private final AiConversationAsyncGenerationProperties asyncProperties;
    private final AiConversationProperties conversationProperties;
    private final HybridBase64UrlCodec idCodec;
    private final Clock clock;
    private final AiConversationMetrics metrics;

    public AiConversationGenerationRecoveryServiceImpl(
            AiConversationGenerationMapper generationMapper,
            AiConversationGenerationPayloadMapper payloadMapper,
            AiConversationSettlementService settlementService,
            AiConversationGenerationCancellationService cancellationService,
            AiConversationGenerationTerminalService terminalService,
            AiConversationGenerationEventPublisher eventPublisher,
            AiConversationAsyncGenerationProperties asyncProperties,
            AiConversationProperties conversationProperties,
            HybridBase64UrlCodec idCodec,
            AiConversationMetrics metrics,
            Clock clock) {
        this.generationMapper = Objects.requireNonNull(generationMapper);
        this.payloadMapper = Objects.requireNonNull(payloadMapper);
        this.settlementService = Objects.requireNonNull(settlementService);
        this.cancellationService = Objects.requireNonNull(cancellationService);
        this.terminalService = Objects.requireNonNull(terminalService);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.asyncProperties = Objects.requireNonNull(asyncProperties);
        this.conversationProperties = Objects.requireNonNull(conversationProperties);
        this.idCodec = Objects.requireNonNull(idCodec);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public int cleanupExpiredTerminalGenerations() {
        List<Integer> terminalStatuses = List.of(
                AiConversationGenerationStatus.SETTLED.code(),
                AiConversationGenerationStatus.REFUNDED.code());
        OffsetDateTime cutoff = clock.instant().atOffset(ZoneOffset.UTC)
                .minus(asyncProperties.terminalRetention());
        List<byte[]> generationIds = generationMapper.findTerminalCleanupCandidates(
                        terminalStatuses,
                        cutoff,
                        conversationProperties.reconciliationBatchSize())
                .stream()
                .map(AiConversationGeneration::getId)
                .toList();
        if (generationIds.isEmpty()) {
            return 0;
        }
        int payloads = payloadMapper.deleteByGenerationIds(generationIds);
        int generations = generationMapper.deleteTerminalByIds(
                generationIds, terminalStatuses);
        if (payloads != generationIds.size() || generations != generationIds.size()) {
            throw new IllegalStateException(
                    "AI Generation cleanup did not delete the complete bounded batch.");
        }
        return generations;
    }

    @Override
    public int recoverDueGenerations() {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        OffsetDateTime publishCutoff = now.minus(
                conversationProperties.reconciliationScanInterval());
        List<AiConversationGeneration> candidates = generationMapper.findRecoveryCandidates(
                AiConversationGenerationStatus.QUEUED.code(),
                AiConversationGenerationStatus.RUNNING.code(),
                AiConversationGenerationStatus.CANCEL_REQUESTED.code(),
                AiConversationGenerationStatus.TERMINAL_PENDING_BILLING.code(),
                AiConversationGenerationObserverStatus.DETACHED.code(),
                publishCutoff,
                now.minus(asyncProperties.maxWorkerDuration())
                        .minus(conversationProperties.reconciliationScanInterval()),
                now.minus(asyncProperties.detachGrace()),
                conversationProperties.reconciliationBatchSize());
        int handled = 0;
        for (AiConversationGeneration generation : candidates) {
            try {
                if (recoverOne(generation)) {
                    handled++;
                }
            } catch (RuntimeException ignoredFailure) {
                // 单条 MQ 或事务恢复失败只能留到下一分钟重试，不能阻塞同批其他任务。
                String publicId = idCodec.encode(generation.getId());
                LOGGER.warn(
                        "event=ai_generation_recovery_failed traceId=recovery generationPrefix={} "
                                + "instanceId={} cause={}",
                        publicId.substring(0, Math.min(8, publicId.length())),
                        asyncProperties.instanceId(),
                        ignoredFailure.getClass().getSimpleName());
            }
        }
        return handled;
    }

    private boolean recoverOne(AiConversationGeneration generation) {
        String generationPublicId = idCodec.encode(generation.getId());
        String usagePublicId = idCodec.encode(generation.getUsageId());
        AiConversationGenerationStatus status = status(generation.getGenerationStatus());
        if ((status == AiConversationGenerationStatus.QUEUED
                        || status == AiConversationGenerationStatus.RUNNING)
                && generation.getObserverStatus()
                        == AiConversationGenerationObserverStatus.DETACHED.code()
                && generation.getDetachedAt() != null
                && !generation.getDetachedAt().plus(asyncProperties.detachGrace())
                        .isAfter(clock.instant().atOffset(ZoneOffset.UTC))) {
            cancellationService.requestDetachedTimeout(
                    generation.getId(),
                    generation.getObserverEpoch(),
                    generation.getDetachedAt(),
                    "recovery");
            return true;
        }
        if (status == AiConversationGenerationStatus.QUEUED) {
            eventPublisher.publishGenerationRequested(
                    generationPublicId, usagePublicId, "recovery");
            return true;
        }
        if (status == AiConversationGenerationStatus.CANCEL_REQUESTED) {
            if (generation.getOwnerInstanceId() == null
                    || generation.getOwnerInstanceId().isBlank()) {
                terminalService.freeze(cancellationTerminal(
                        generation, "AI_GENERATION_CANCELLED_BEFORE_OWNER"));
                return true;
            }
            if (generation.getCancelRequestedAt() != null
                    && !generation.getCancelRequestedAt()
                            .plus(asyncProperties.maxWorkerDuration())
                            .isAfter(clock.instant().atOffset(ZoneOffset.UTC))) {
                // Owner 永久失联后停止投递孤儿取消命令；视频无法证明供应商成本时转对账，其他请求沿用系统失败退款。
                boolean uncertainVideo = markVideoOwnerLossUncertain(generation);
                terminalService.freeze(new AiConversationGenerationTerminalCommand(
                        generation.getId(),
                        AiConversationGenerationTerminalType.SYSTEM_FAILED,
                        uncertainVideo
                                ? "AI_VIDEO_XAI_RESULT_UNCERTAIN"
                                : "AI_GENERATION_CANCEL_OWNER_LOST",
                        "",
                        "[]",
                        null,
                        "SYSTEM_FAILED",
                        null,
                        "recovery"));
                return true;
            }
            eventPublisher.publishCancelRequested(
                    generationPublicId,
                    generation.getCancelSource(),
                    1,
                    generation.getOwnerInstanceId(),
                    "recovery");
            return true;
        }
        if (status == AiConversationGenerationStatus.TERMINAL_PENDING_BILLING) {
            eventPublisher.publishTerminated(
                    generationPublicId,
                    usagePublicId,
                    generation.getTerminalType(),
                    generation.getTerminalReason(),
                    generation.getTerminalVersion(),
                    "recovery");
            return true;
        }
        if (status == AiConversationGenerationStatus.RUNNING) {
            boolean uncertainVideo = markVideoOwnerLossUncertain(generation);
            terminalService.freeze(new AiConversationGenerationTerminalCommand(
                    generation.getId(),
                    AiConversationGenerationTerminalType.SYSTEM_FAILED,
                    uncertainVideo
                            ? "AI_VIDEO_XAI_RESULT_UNCERTAIN"
                            : "AI_GENERATION_OWNER_LOST",
                    "",
                    "[]",
                    null,
                    "SYSTEM_FAILED",
                    null,
                    "recovery"));
            return true;
        }
        return false;
    }

    private boolean markVideoOwnerLossUncertain(
            AiConversationGeneration generation) {
        if (generation.getVideoStage() == null) {
            return false;
        }
        // Owner 丢失后无法证明创建 POST 是否到达 xAI；保留预扣并进入人工对账，禁止按零成本退款。
        if (generationMapper.updateVideoStage(
                generation.getId(),
                AiConversationVideoGenerationStage.XAI_RESULT_UNCERTAIN.name()) != 1) {
            throw new IllegalStateException(
                    "AI video owner-loss stage update did not affect one row.");
        }
        return true;
    }

    private AiConversationGenerationTerminalCommand cancellationTerminal(
            AiConversationGeneration generation,
            String fallbackReason) {
        AiConversationGenerationTerminalType type =
                "ADMIN_CANCEL".equals(generation.getCancelSource())
                        ? AiConversationGenerationTerminalType.ADMIN_CANCELLED
                        : AiConversationGenerationTerminalType.CLIENT_CANCELLED;
        return new AiConversationGenerationTerminalCommand(
                generation.getId(),
                type,
                Objects.requireNonNullElse(generation.getCancelSource(), fallbackReason),
                "",
                "[]",
                null,
                type.name(),
                null,
                "recovery");
    }

    @Override
    @Transactional
    public void markBillingReconcileRequired(
            byte[] generationId,
            String failureCode) {
        AiConversationGeneration generation = generationMapper.findByIdForUpdate(generationId);
        if (generation == null
                || generation.getGenerationStatus()
                != AiConversationGenerationStatus.TERMINAL_PENDING_BILLING.code()) {
            return;
        }
        settlementService.markReconcileRequired(generation.getUsageId(), failureCode);
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if (generationMapper.markReconcileRequired(
                generationId,
                List.of(AiConversationGenerationStatus.TERMINAL_PENDING_BILLING.code()),
                AiConversationGenerationStatus.RECONCILE_REQUIRED.code(),
                failureCode,
                now) != 1) {
            throw new IllegalStateException("AI Generation reconcile update did not affect one row.");
        }
        metrics.generationReconcileRequired();
    }

    private static AiConversationGenerationStatus status(int code) {
        for (AiConversationGenerationStatus value : AiConversationGenerationStatus.values()) {
            if (value.code() == code) {
                return value;
            }
        }
        throw new IllegalStateException("Unknown AI Generation status.");
    }
}
