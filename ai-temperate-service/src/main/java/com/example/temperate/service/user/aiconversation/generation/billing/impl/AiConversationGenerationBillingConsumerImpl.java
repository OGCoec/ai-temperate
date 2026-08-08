package com.example.temperate.service.user.aiconversation.generation.billing.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationPayloadMapper;
import com.example.temperate.mapper.ai.AiModelUsageDetailMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.model.ai.entity.AiConversationGenerationPayload;
import com.example.temperate.model.ai.entity.AiModelUsageDetail;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentFinalization;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedMedia;
import com.example.temperate.service.user.aiconversation.billing.AiConversationReservation;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementCommand;
import com.example.temperate.service.user.aiconversation.billing.ProviderCostReservationMetering;
import com.example.temperate.service.user.aiconversation.billing.TokenReservationMetering;
import com.example.temperate.service.user.aiconversation.billing.VideoProviderCostReservationMetering;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionCoordinator;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionRequestResult;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionTrigger;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextSnapshot;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextStore;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextWriteOutcome;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalType;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBilledEvent;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingCommand;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingConsumer;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingMode;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingResult;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingTransactionService;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationTerminated;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationMessageRejectedException;
import com.example.temperate.service.user.aiconversation.generation.input.AiConversationGenerationInputCodec;
import com.example.temperate.service.user.aiconversation.generation.input.AiConversationGenerationInputSnapshot;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewBroker;
import com.example.temperate.service.user.aiconversation.image.AiConversationPersistedGeneratedAttachmentCodec;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteredUsage;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.AiConversationProviderCostUsage;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingAction;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingDecision;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingPolicy;
import com.example.temperate.service.user.aiconversation.text.AiConversationTextTokenizer;
import com.example.temperate.service.user.aiconversation.video.AiConversationPersistedVideoResult;
import com.example.temperate.service.user.aiconversation.video.AiConversationPersistedVideoResultCodec;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationStage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 读取冻结证据并选择成功、取消估算或全额退款路径，金额计算继续完全委托既有 Billing Policy 与 Settlement。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationBillingConsumerImpl
        implements AiConversationGenerationBillingConsumer {

    private static final TypeReference<List<AiConversationGeneratedMedia>> GENERATED_MEDIA =
            new TypeReference<>() { };

    private final AiConversationGenerationMapper generationMapper;
    private final AiConversationGenerationPayloadMapper payloadMapper;
    private final AiModelUsageDetailMapper detailMapper;
    private final AiConversationTerminalBillingPolicy billingPolicy;
    private final AiConversationGenerationBillingTransactionService transactionService;
    private final AiConversationAttachmentService attachmentService;
    private final AiConversationTextTokenizer tokenizer;
    private final AiConversationContextStore contextStore;
    private final HybridBase64UrlCodec idCodec;
    private final PublicIdCodec publicIdCodec;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AiConversationMetrics metrics;
    private final AiConversationGenerationInputCodec inputCodec;
    private final AiConversationPersistedGeneratedAttachmentCodec generatedAttachmentCodec;
    private final AiConversationPersistedVideoResultCodec persistedVideoResultCodec;
    private final AiConversationImagePreviewBroker previewBroker;
    private final AiConversationCompactionCoordinator compactionCoordinator;

    public AiConversationGenerationBillingConsumerImpl(
            AiConversationGenerationMapper generationMapper,
            AiConversationGenerationPayloadMapper payloadMapper,
            AiModelUsageDetailMapper detailMapper,
            AiConversationTerminalBillingPolicy billingPolicy,
            AiConversationGenerationBillingTransactionService transactionService,
            AiConversationAttachmentService attachmentService,
            AiConversationTextTokenizer tokenizer,
            AiConversationContextStore contextStore,
            HybridBase64UrlCodec idCodec,
            PublicIdCodec publicIdCodec,
            ObjectMapper objectMapper,
            AiConversationMetrics metrics,
            AiConversationGenerationInputCodec inputCodec,
            AiConversationPersistedGeneratedAttachmentCodec generatedAttachmentCodec,
            AiConversationPersistedVideoResultCodec persistedVideoResultCodec,
            ApplicationEventPublisher eventPublisher,
            AiConversationImagePreviewBroker previewBroker,
            AiConversationCompactionCoordinator compactionCoordinator) {
        this.generationMapper = Objects.requireNonNull(generationMapper);
        this.payloadMapper = Objects.requireNonNull(payloadMapper);
        this.detailMapper = Objects.requireNonNull(detailMapper);
        this.billingPolicy = Objects.requireNonNull(billingPolicy);
        this.transactionService = Objects.requireNonNull(transactionService);
        this.attachmentService = Objects.requireNonNull(attachmentService);
        this.tokenizer = Objects.requireNonNull(tokenizer);
        this.contextStore = Objects.requireNonNull(contextStore);
        this.idCodec = Objects.requireNonNull(idCodec);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.metrics = Objects.requireNonNull(metrics);
        this.inputCodec = Objects.requireNonNull(inputCodec);
        this.generatedAttachmentCodec = Objects.requireNonNull(generatedAttachmentCodec);
        this.persistedVideoResultCodec = Objects.requireNonNull(persistedVideoResultCodec);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.previewBroker = Objects.requireNonNull(previewBroker);
        this.compactionCoordinator = Objects.requireNonNull(compactionCoordinator);
    }

    @Override
    public void consume(AiConversationGenerationTerminated terminal, String traceId) {
        byte[] generationId = idCodec.decode(terminal.generationPublicId());
        AiConversationGeneration generation = generationMapper.findById(generationId);
        if (generation == null) {
            return;
        }
        requireAuthoritativeTerminal(generation, terminal);
        if (generation.getGenerationStatus()
                != AiConversationGenerationStatus.TERMINAL_PENDING_BILLING.code()) {
            AiConversationGenerationStatus existing = generationStatus(
                    generation.getGenerationStatus());
            if (existing.terminal()) {
                // 资金事务已提交但 Redis 终态通知失败时，Rabbit 重投只补发展示事件，绝不重复结算。
                AiConversationGenerationPayload frozenPayload =
                        payloadMapper.findByGenerationId(generationId);
                long messageId = frozenPayload == null
                        || frozenPayload.getConversationMessageId() == null
                                ? 0L
                                : frozenPayload.getConversationMessageId();
                short requestedImageCount = 0;
                List<AiConversationAttachment> responseAttachments = List.of();
                AiConversationPersistedVideoResult persistedVideoResult = null;
                boolean videoRequest = false;
                if (frozenPayload != null) {
                    AiConversationGenerationInputSnapshot frozenInput =
                            inputCodec.decode(frozenPayload.getInputAttachmentsJson());
                    if (frozenInput.imageGeneration() != null) {
                        requestedImageCount =
                                frozenInput.imageGeneration().outputCount();
                        if (AiConversationGenerationTerminalType.COMPLETED.name()
                                .equals(terminal.terminalType())
                                && frozenPayload.getAssistantAttachmentsJson() != null
                                && frozenPayload.getAssistantAttachmentsJson()
                                        .stripLeading().startsWith("{")) {
                            // 图片 Worker 已把正式 URL 信封冻结在 Payload；幂等补发只解码元数据，禁止再次触发 OSS。
                            responseAttachments = generatedAttachmentCodec.decode(
                                    frozenPayload.getAssistantAttachmentsJson());
                        }
                    } else if (frozenInput.videoGeneration() != null
                            && AiConversationGenerationTerminalType.COMPLETED.name()
                                    .equals(terminal.terminalType())
                            && frozenPayload.getAssistantAttachmentsJson() != null
                            && frozenPayload.getAssistantAttachmentsJson()
                                    .stripLeading().startsWith("{")) {
                        videoRequest = true;
                        persistedVideoResult = persistedVideoResultCodec.decode(
                                frozenPayload.getAssistantAttachmentsJson());
                        responseAttachments = List.of(
                                persistedVideoResult.attachment());
                    } else if (frozenInput.videoGeneration() != null) {
                        videoRequest = true;
                    }
                }
                if (videoRequest
                        && existing == AiConversationGenerationStatus.RECONCILE_REQUIRED
                        && generationMapper.updateVideoStage(
                                generationId,
                                AiConversationVideoGenerationStage
                                        .BILLING_RECONCILE_REQUIRED.name()) != 1) {
                    throw new IllegalStateException(
                            "AI video reconcile stage replay did not affect one row.");
                }
                publishBilled(
                        terminal,
                        existing.name(),
                        messageId,
                        requestedImageCount,
                        responseAttachments,
                        persistedVideoResult,
                        videoRequest,
                        effectiveVideoStage(
                                videoRequest,
                                existing.name(),
                                generation.getVideoStage()),
                        AiConversationGenerationTerminalType.COMPLETED.name()
                                        .equals(terminal.terminalType())
                                ? refreshContextAfterCompletion(
                                        generation,
                                        idCodec.encode(
                                                generation.getConversationId()))
                                : null);
            }
            return;
        }
        if (generation.getTerminalVersion() != terminal.terminalVersion()) {
            throw new IllegalStateException("AI Generation terminal version is stale.");
        }
        AiConversationGenerationPayload payload = payloadMapper.findByGenerationId(generationId);
        AiModelUsageDetail detail = detailMapper.findByUsageId(generation.getUsageId());
        if (payload == null || detail == null) {
            throw new IllegalStateException("AI Generation billing evidence is incomplete.");
        }
        AiConversationGenerationInputSnapshot inputSnapshot =
                inputCodec.decode(payload.getInputAttachmentsJson());
        AiConversationContent user = new AiConversationContent(
                payload.getInputText(), inputSnapshot.attachments());
        AiConversationMeteredUsage usage = reportedUsage(payload);
        AiConversationGenerationTerminalType type = AiConversationGenerationTerminalType.valueOf(
                terminal.terminalType());
        boolean costEvidenceMissing = type
                == AiConversationGenerationTerminalType.COMPLETED
                && payload.getMeteringBasis()
                        == AiConversationMeteringBasis.PROVIDER_COST_TICKS.code()
                && payload.getProviderCostTicks() == null
                && payload.getMeteringEvidenceJson() != null;

        AiConversationGenerationBillingCommand command;
        AiConversationAttachmentFinalization finalized = null;
        if (type == AiConversationGenerationTerminalType.COMPLETED
                && (usage != null || costEvidenceMissing)) {
            // 消息 ID 先原子绑定到 Payload，Rabbit 重投时附件对象路径保持稳定，避免重复创建不同对象。
            long messageId = transactionService.getOrReserveMessageId(generationId);
            String conversationPublicId = idCodec.encode(generation.getConversationId());
            if (inputSnapshot.videoGeneration() != null) {
                // 视频对象已由 FC 写入 OSS；Billing 仅公开化输入附件并读取结果信封，禁止再次下载或搬运视频。
                AiConversationAttachmentFinalization finalizedInputs =
                        attachmentService.finalizeAttachments(
                                publicIdCodec.encode(generation.getLoginIdentityId()),
                                conversationPublicId,
                                publicIdCodec.encode(messageId),
                                user.attachments(),
                                List.of());
                AiConversationPersistedVideoResult persistedVideo =
                        persistedVideoResultCodec.decode(
                                payload.getAssistantAttachmentsJson());
                finalized = videoFinalization(finalizedInputs, persistedVideo);
            } else if (inputSnapshot.imageGeneration() == null) {
                finalized = attachmentService.finalizeAttachments(
                        publicIdCodec.encode(generation.getLoginIdentityId()),
                        conversationPublicId,
                        publicIdCodec.encode(messageId),
                        user.attachments(),
                        generatedMedia(payload.getAssistantAttachmentsJson()));
            } else {
                // 图片字节已在 Worker 中有界上传；Billing 只读取 URL 信封，禁止再次解码或保存 Base64。
                AiConversationAttachmentFinalization finalizedInputs =
                        attachmentService.finalizeAttachments(
                                publicIdCodec.encode(generation.getLoginIdentityId()),
                                conversationPublicId,
                                publicIdCodec.encode(messageId),
                                user.attachments(),
                                List.of());
                finalized = new AiConversationAttachmentFinalization(
                        finalizedInputs.inputAttachments(),
                        generatedAttachmentCodec.decode(
                                payload.getAssistantAttachmentsJson()),
                        finalizedInputs.createdObjectKeys(),
                        finalizedInputs.partialFailure());
            }
            AiConversationContent finalizedUser = new AiConversationContent(
                    user.text(), finalized.inputAttachments());
            AiConversationContent assistant = new AiConversationContent(
                    payload.getAssistantText(), finalized.responseAttachments());
            command = new AiConversationGenerationBillingCommand(
                    generationId,
                    terminal.terminalVersion(),
                    costEvidenceMissing
                            ? AiConversationGenerationBillingMode.COMPLETE_RECONCILE
                            : AiConversationGenerationBillingMode.COMPLETE,
                    settlementCommand(
                            generation,
                            payload,
                            messageId,
                            finalizedUser,
                            assistant,
                            usage,
                            payload.getModelFinishReason() == null
                                    ? "STOP" : payload.getModelFinishReason(),
                            traceId),
                    terminal.terminalType(),
                    costEvidenceMissing
                            ? inputSnapshot.videoGeneration() == null
                                    ? "AI_IMAGE_COST_EVIDENCE_MISSING"
                                    : "AI_VIDEO_COST_EVIDENCE_MISSING"
                            : terminal.terminalReason());
        } else if (inputSnapshot.videoGeneration() != null
                && payload.getMeteringBasis()
                        == AiConversationMeteringBasis.PROVIDER_COST_TICKS.code()) {
            if (usage != null) {
                // xAI 已报告实际成本时，即使 OSS 搬运或任务交付失败也必须按真实供应商成本结算。
                command = new AiConversationGenerationBillingCommand(
                        generationId,
                        terminal.terminalVersion(),
                        AiConversationGenerationBillingMode.INTERRUPTED,
                        settlementCommand(
                                generation,
                                payload,
                                null,
                                user,
                                new AiConversationContent("", List.of()),
                                usage,
                                type.name(),
                                traceId),
                        type.name(),
                        terminal.terminalReason());
            } else if ("AI_VIDEO_XAI_REJECTED".equals(
                            terminal.terminalReason())
                    || ((type == AiConversationGenerationTerminalType.CLIENT_CANCELLED
                                    || type == AiConversationGenerationTerminalType.ADMIN_CANCELLED)
                            && payload.getUpstreamRequestId() == null
                            && payload.getMeteringEvidenceJson() == null)) {
                command = new AiConversationGenerationBillingCommand(
                        generationId,
                        terminal.terminalVersion(),
                        AiConversationGenerationBillingMode.REFUND_FULL,
                        null,
                        type.name(),
                        terminal.terminalReason());
            } else {
                // POST 或轮询结果无法确认时保留预扣并进入人工对账，禁止把未知成本当成零成本退款。
                command = new AiConversationGenerationBillingCommand(
                        generationId,
                        terminal.terminalVersion(),
                        AiConversationGenerationBillingMode.RECONCILE_ONLY,
                        null,
                        type.name(),
                        Objects.requireNonNullElse(
                                terminal.terminalReason(),
                                "AI_VIDEO_XAI_RESULT_UNCERTAIN"));
            }
        } else if (type == AiConversationGenerationTerminalType.UPSTREAM_FAILED
                || type == AiConversationGenerationTerminalType.SYSTEM_FAILED
                || type == AiConversationGenerationTerminalType.COMPLETED) {
            String finishReason = type == AiConversationGenerationTerminalType.UPSTREAM_FAILED
                    ? "UPSTREAM_FAILED" : "SYSTEM_FAILED";
            String failureCode = type == AiConversationGenerationTerminalType.COMPLETED
                    ? "AI_STREAM_TERMINATED_WITHOUT_USAGE"
                    : terminal.terminalReason();
            command = new AiConversationGenerationBillingCommand(
                    generationId,
                    terminal.terminalVersion(),
                    AiConversationGenerationBillingMode.REFUND_FULL,
                    null,
                    finishReason,
                    failureCode);
        } else {
            if (payload.getMeteringBasis()
                    == AiConversationMeteringBasis.PROVIDER_COST_TICKS.code()) {
                command = new AiConversationGenerationBillingCommand(
                        generationId,
                        terminal.terminalVersion(),
                        AiConversationGenerationBillingMode.REFUND_FULL,
                        null,
                        type == AiConversationGenerationTerminalType.ADMIN_CANCELLED
                                ? "ADMIN_CANCELLED" : "CLIENT_CANCELLED",
                        terminal.terminalReason());
            } else {
                AiConversationReservation reservation = reservation(generation, detail);
                AiConversationUsage tokenUsage = usage instanceof AiConversationUsage value
                        ? value : null;
                AiConversationTerminalBillingDecision decision =
                        billingPolicy.clientCancellation(
                                reservation, tokenUsage, payload.getAssistantText());
                if (decision.action() == AiConversationTerminalBillingAction.REFUND_FULL) {
                    command = new AiConversationGenerationBillingCommand(
                            generationId,
                            terminal.terminalVersion(),
                            AiConversationGenerationBillingMode.REFUND_FULL,
                            null,
                            type == AiConversationGenerationTerminalType.ADMIN_CANCELLED
                                    ? "ADMIN_CANCELLED" : "CLIENT_CANCELLED",
                            decision.failureCode());
                } else {
                    AiConversationUsage billableUsage = decision.usage();
                    command = new AiConversationGenerationBillingCommand(
                            generationId,
                            terminal.terminalVersion(),
                            AiConversationGenerationBillingMode.INTERRUPTED,
                            settlementCommand(
                                    generation,
                                    payload,
                                    null,
                                    user,
                                    new AiConversationContent(
                                            payload.getAssistantText(), List.of()),
                                    billableUsage,
                                    type == AiConversationGenerationTerminalType.ADMIN_CANCELLED
                                            ? "ADMIN_CANCELLED" : "CLIENT_CANCELLED",
                                    traceId),
                            type.name(),
                            decision.failureCode());
                }
            }
        }

        long billingStarted = System.nanoTime();
        boolean transactionCommitted = false;
        try {
            AiConversationGenerationBillingResult result = transactionService.settle(command);
            transactionCommitted = true;
            if (result.applied()) {
                AiConversationCompactionRequestResult contextRefresh = null;
                if (command.mode() == AiConversationGenerationBillingMode.COMPLETE
                        || command.mode()
                                == AiConversationGenerationBillingMode.COMPLETE_RECONCILE) {
                    contextRefresh = commitPersistedContext(
                            generation,
                            payload,
                            result.messageId(),
                            command.settlementCommand());
                }
                List<AiConversationAttachment> responseAttachments =
                        (command.mode() == AiConversationGenerationBillingMode.COMPLETE
                                || command.mode()
                                        == AiConversationGenerationBillingMode.COMPLETE_RECONCILE)
                                && command.settlementCommand() != null
                                ? command.settlementCommand().assistant().attachments()
                                : List.of();
                AiConversationPersistedVideoResult persistedVideoResult =
                        inputSnapshot.videoGeneration() != null
                                        && payload.getAssistantAttachmentsJson() != null
                                        && payload.getAssistantAttachmentsJson()
                                                .stripLeading().startsWith("{")
                                ? persistedVideoResultCodec.decode(
                                        payload.getAssistantAttachmentsJson())
                                : null;
                if (inputSnapshot.videoGeneration() != null
                        && "RECONCILE_REQUIRED".equals(result.finalStatus())
                        && generationMapper.updateVideoStage(
                                generationId,
                                AiConversationVideoGenerationStage
                                        .BILLING_RECONCILE_REQUIRED.name()) != 1) {
                    throw new IllegalStateException(
                            "AI video reconcile stage update did not affect one row.");
                }
                publishBilled(
                        terminal,
                        result.finalStatus(),
                        result.messageId(),
                        inputSnapshot.imageGeneration() == null
                                ? (short) 0
                                : inputSnapshot.imageGeneration().outputCount(),
                        responseAttachments,
                        persistedVideoResult,
                        inputSnapshot.videoGeneration() != null,
                        effectiveVideoStage(
                                inputSnapshot.videoGeneration() != null,
                                result.finalStatus(),
                                generation.getVideoStage()),
                        contextRefresh);
            }
            metrics.generationBilling(
                    Duration.ofNanos(System.nanoTime() - billingStarted), "success");
        } catch (RuntimeException failure) {
            metrics.generationBilling(
                    Duration.ofNanos(System.nanoTime() - billingStarted), "failed");
            if (!transactionCommitted && finalized != null) {
                attachmentService.compensateCreatedObjects(finalized.createdObjectKeys());
            }
            throw failure;
        }
    }

    private void publishBilled(
            AiConversationGenerationTerminated terminal,
            String finalStatus,
            long messageId,
            short requestedImageCount,
            List<AiConversationAttachment> responseAttachments,
            AiConversationPersistedVideoResult persistedVideoResult,
            boolean videoRequest,
            String videoStage,
            AiConversationCompactionRequestResult contextRefresh) {
        // 图片字节已经在 Worker 中释放；终态只发送正式附件、请求数量和可选消息 ID，供浏览器重建缺失槽位。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generationPublicId", terminal.generationPublicId());
        data.put("usagePublicId", terminal.usagePublicId());
        data.put("messagePublicId", messageId > 0L
                ? publicIdCodec.encode(messageId)
                : "");
        data.put("status", finalStatus);
        data.put("terminalType", terminal.terminalType());
        data.put("terminalReason", Objects.requireNonNullElse(
                terminal.terminalReason(), "unavailable"));
        data.put("requestedImageCount", requestedImageCount);
        data.put("attachments", List.copyOf(responseAttachments));
        if (persistedVideoResult != null) {
            data.put("durationMillis", persistedVideoResult.durationMillis());
            data.put("width", persistedVideoResult.width());
            data.put("height", persistedVideoResult.height());
            data.put("byteSize", persistedVideoResult.byteSize());
            data.put("contentType", persistedVideoResult.contentType());
            data.put("videoCodec", persistedVideoResult.videoCodec());
            data.put("storageProvider", persistedVideoResult.storageProvider());
        } else if (videoRequest) {
            data.put("errorCode", Objects.requireNonNullElse(
                    terminal.terminalReason(), "AI_VIDEO_FAILED"));
            data.put("failureStage", Objects.requireNonNullElse(
                    videoStage, "BILLING_RECONCILE_REQUIRED"));
        }
        if (contextRefresh != null) {
            data.put("contextUsage", contextRefresh.usage());
            data.put(
                    "compactionOperationPublicId",
                    contextRefresh.operation() == null
                            ? null
                            : contextRefresh.operation().operationPublicId());
        }
        eventPublisher.publishEvent(new AiConversationGenerationBilledEvent(
                terminal.generationPublicId(),
                videoRequest
                        ? persistedVideoResult == null
                                ? "video_failed"
                                : "video_ready"
                        : "completed",
                json(data)));
        // 终态事件已经进入既有输出通道后统一释放所有预览槽位；没有浏览器观察者时也不会等 TTL 才回收大图。
        previewBroker.release(terminal.generationPublicId());
    }

    private static String effectiveVideoStage(
            boolean videoRequest,
            String finalStatus,
            String persistedStage) {
        if (!videoRequest) {
            return null;
        }
        return "RECONCILE_REQUIRED".equals(finalStatus)
                ? AiConversationVideoGenerationStage.BILLING_RECONCILE_REQUIRED.name()
                : persistedStage;
    }

    static AiConversationAttachmentFinalization videoFinalization(
            AiConversationAttachmentFinalization finalizedInputs,
            AiConversationPersistedVideoResult persistedVideo) {
        Objects.requireNonNull(finalizedInputs);
        Objects.requireNonNull(persistedVideo);
        // 视频对象已在 Worker 终态前由 FC 持久化；Billing 失败只能补偿本次复制的输入附件，禁止删除终态引用的视频。
        return new AiConversationAttachmentFinalization(
                finalizedInputs.inputAttachments(),
                List.of(persistedVideo.attachment()),
                finalizedInputs.createdObjectKeys(),
                finalizedInputs.partialFailure());
    }

    private AiConversationSettlementCommand settlementCommand(
            AiConversationGeneration generation,
            AiConversationGenerationPayload payload,
            Long messageId,
            AiConversationContent user,
            AiConversationContent assistant,
            AiConversationMeteredUsage usage,
            String finishReason,
            String traceId) {
        return new AiConversationSettlementCommand(
                generation.getUsageId(),
                messageId,
                user,
                assistant,
                tokenizer.tokenize(user.text()),
                usage,
                payload.getUpstreamRequestId(),
                finishReason,
                new com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTraceContext(
                        safeTraceId(traceId),
                        "unavailable",
                        idCodec.encode(generation.getUsageId()),
                        idCodec.encode(generation.getConversationId()),
                        publicIdCodec.encode(generation.getModelId()),
                        System.nanoTime()));
    }

    private AiConversationReservation reservation(
            AiConversationGeneration generation,
            AiModelUsageDetail detail) {
        if (detail.getMeteringBasis()
                == AiConversationMeteringBasis.PROVIDER_COST_TICKS.code()) {
            return new AiConversationReservation(
                    generation.getConversationId(),
                    generation.getUsageId(),
                    detail.getConversationMessageId(),
                    0,
                    detail.getReservedQuotaMinor(),
                    new ProviderCostReservationMetering(
                            detail.getRequestedOutputCount()),
                    false,
                    false);
        }
        return new AiConversationReservation(
                generation.getConversationId(),
                generation.getUsageId(),
                detail.getConversationMessageId(),
                0,
                detail.getReservedQuotaMinor(),
                detail.getEstimatedPromptTokens(),
                detail.getMaxOutputTokens(),
                detail.getInputRatioSnapshot(),
                detail.getCachedInputRatioSnapshot(),
                detail.getOutputRatioSnapshot(),
                false,
                false);
    }

    private AiConversationMeteredUsage reportedUsage(
            AiConversationGenerationPayload payload) {
        if (payload.getMeteringBasis()
                == AiConversationMeteringBasis.PROVIDER_COST_TICKS.code()) {
            return payload.getProviderCostTicks() == null
                    ? null
                    : new AiConversationProviderCostUsage(
                            payload.getProviderCostTicks());
        }
        if (payload.getPromptTokens() == null
                || payload.getCompletionTokens() == null
                || payload.getCachedPromptTokens() == null
                || payload.getReasoningTokens() == null) {
            return null;
        }
        return new AiConversationUsage(
                payload.getPromptTokens(),
                payload.getCachedPromptTokens(),
                payload.getCompletionTokens(),
                payload.getReasoningTokens());
    }

    private List<AiConversationGeneratedMedia> generatedMedia(String json) {
        return read(json == null ? "[]" : json, GENERATED_MEDIA);
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("AI Generation billing evidence is invalid.", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("AI Generation billed event is invalid.", exception);
        }
    }

    private static String safeTraceId(String traceId) {
        return traceId == null || !traceId.matches("[A-Za-z0-9_-]{1,128}")
                ? "unavailable" : traceId;
    }

    private static AiConversationGenerationStatus generationStatus(int code) {
        for (AiConversationGenerationStatus value : AiConversationGenerationStatus.values()) {
            if (value.code() == code) {
                return value;
            }
        }
        throw new IllegalStateException("Unknown AI Generation status.");
    }

    private void requireAuthoritativeTerminal(
            AiConversationGeneration generation,
            AiConversationGenerationTerminated terminal) {
        // Rabbit 消息只负责唤醒；终态类型、原因、版本和 Usage 必须与 PostgreSQL 冻结事实完全一致。
        boolean matches = terminal.usagePublicId().equals(
                        idCodec.encode(generation.getUsageId()))
                && terminal.terminalVersion() == generation.getTerminalVersion()
                && Objects.equals(terminal.terminalType(), generation.getTerminalType())
                && Objects.equals(terminal.terminalReason(), generation.getTerminalReason());
        if (!matches) {
            throw new AiConversationGenerationMessageRejectedException(
                    "AI Generation terminal message does not match authoritative evidence.");
        }
    }

    private AiConversationCompactionRequestResult commitPersistedContext(
            AiConversationGeneration generation,
            AiConversationGenerationPayload payload,
            long messageId,
            AiConversationSettlementCommand settlement) {
        if (payload.getContextGeneration() == null
                || payload.getEphemeralOrdinal() == null
                || messageId <= 0L
                || settlement == null) {
            invalidateContext(generation);
            return null;
        }
        String conversationPublicId = idCodec.encode(generation.getConversationId());
        String contextGeneration = payload.getContextGeneration();
        AiConversationContextWriteOutcome outcome =
                AiConversationContextWriteOutcome.UNAVAILABLE;
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                outcome = contextStore.commitPersistedTurn(
                        conversationPublicId,
                        contextGeneration,
                        messageId,
                        payload.getEphemeralOrdinal(),
                        settlement.user(),
                        settlement.assistant());
                if (outcome == AiConversationContextWriteOutcome.APPLIED) {
                    return refreshContextAfterCompletion(
                            generation, conversationPublicId);
                }
                if (outcome == AiConversationContextWriteOutcome.UNAVAILABLE) {
                    break;
                }
                AiConversationContextSnapshot current = contextStore
                        .find(conversationPublicId)
                        .orElse(null);
                if (current == null) {
                    break;
                }
                contextGeneration = current.generation();
            }
        } catch (RuntimeException ignoredFailure) {
            outcome = AiConversationContextWriteOutcome.UNAVAILABLE;
        }
        metrics.context(outcome == AiConversationContextWriteOutcome.GENERATION_MISMATCH
                ? "generation_conflict" : "unavailable");
        invalidateContext(generation);
        return null;
    }

    private AiConversationCompactionRequestResult refreshContextAfterCompletion(
            AiConversationGeneration generation,
            String conversationPublicId) {
        try {
            return compactionCoordinator.request(
                    generation.getConversationId(),
                    conversationPublicId,
                    generation.getModelId(),
                    AiConversationCompactionTrigger.ANSWER_COMPLETED);
        } catch (RuntimeException ignoredFailure) {
            // 消息和资金事务已经提交；派生用量事件或压缩调度失败不得反向破坏权威终态。
            return null;
        }
    }

    private void invalidateContext(AiConversationGeneration generation) {
        try {
            contextStore.invalidate(idCodec.encode(generation.getConversationId()));
        } catch (RuntimeException ignoredFailure) {
            // PostgreSQL 消息和资金已经提交，Redis 删除失败只能由绝对 TTL 收敛，禁止反向回滚结算。
        }
    }
}
