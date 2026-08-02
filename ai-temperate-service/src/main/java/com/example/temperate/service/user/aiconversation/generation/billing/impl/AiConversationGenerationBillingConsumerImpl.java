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
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingAction;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingDecision;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingPolicy;
import com.example.temperate.service.user.aiconversation.text.AiConversationTextTokenizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;
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

    private static final TypeReference<List<AiConversationAttachment>> ATTACHMENTS =
            new TypeReference<>() { };
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
            ApplicationEventPublisher eventPublisher) {
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
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
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
                publishBilled(terminal, existing.name());
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
        AiConversationContent user = new AiConversationContent(
                payload.getInputText(), attachments(payload.getInputAttachmentsJson()));
        AiConversationUsage usage = reportedUsage(payload);
        AiConversationGenerationTerminalType type = AiConversationGenerationTerminalType.valueOf(
                terminal.terminalType());

        AiConversationGenerationBillingCommand command;
        AiConversationAttachmentFinalization finalized = null;
        if (type == AiConversationGenerationTerminalType.COMPLETED && usage != null) {
            // 消息 ID 先原子绑定到 Payload，Rabbit 重投时附件对象路径保持稳定，避免重复创建不同对象。
            long messageId = transactionService.getOrReserveMessageId(generationId);
            String conversationPublicId = idCodec.encode(generation.getConversationId());
            finalized = attachmentService.finalizeAttachments(
                    publicIdCodec.encode(generation.getLoginIdentityId()),
                    conversationPublicId,
                    publicIdCodec.encode(messageId),
                    user.attachments(),
                    generatedMedia(payload.getAssistantAttachmentsJson()));
            AiConversationContent finalizedUser = new AiConversationContent(
                    user.text(), finalized.inputAttachments());
            AiConversationContent assistant = new AiConversationContent(
                    payload.getAssistantText(), finalized.responseAttachments());
            command = new AiConversationGenerationBillingCommand(
                    generationId,
                    terminal.terminalVersion(),
                    AiConversationGenerationBillingMode.COMPLETE,
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
                    terminal.terminalReason());
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
            AiConversationReservation reservation = reservation(generation, detail);
            AiConversationTerminalBillingDecision decision = billingPolicy.clientCancellation(
                    reservation, usage, payload.getAssistantText());
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
                                new AiConversationContent(payload.getAssistantText(), List.of()),
                                billableUsage,
                                type == AiConversationGenerationTerminalType.ADMIN_CANCELLED
                                        ? "ADMIN_CANCELLED" : "CLIENT_CANCELLED",
                                traceId),
                        type.name(),
                        decision.failureCode());
            }
        }

        long billingStarted = System.nanoTime();
        boolean transactionCommitted = false;
        try {
            AiConversationGenerationBillingResult result = transactionService.settle(command);
            transactionCommitted = true;
            if (result.applied()) {
                if (command.mode() == AiConversationGenerationBillingMode.COMPLETE) {
                    commitPersistedContext(
                            generation,
                            payload,
                            result.messageId(),
                            command.settlementCommand());
                }
                publishBilled(terminal, result.finalStatus());
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
            String finalStatus) {
        eventPublisher.publishEvent(new AiConversationGenerationBilledEvent(
                terminal.generationPublicId(),
                "completed",
                json(Map.of(
                        "generationPublicId", terminal.generationPublicId(),
                        "usagePublicId", terminal.usagePublicId(),
                        "status", finalStatus,
                        "terminalType", terminal.terminalType(),
                        "terminalReason", Objects.requireNonNullElse(
                                terminal.terminalReason(), "unavailable")))));
    }

    private AiConversationSettlementCommand settlementCommand(
            AiConversationGeneration generation,
            AiConversationGenerationPayload payload,
            Long messageId,
            AiConversationContent user,
            AiConversationContent assistant,
            AiConversationUsage usage,
            String finishReason,
            String traceId) {
        return new AiConversationSettlementCommand(
                generation.getUsageId(),
                messageId,
                user,
                assistant,
                tokenizer.tokenize(user.text()),
                usage.promptTokens(),
                usage.cachedPromptTokens(),
                usage.completionTokens(),
                usage.reasoningTokens(),
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

    private AiConversationUsage reportedUsage(AiConversationGenerationPayload payload) {
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

    private List<AiConversationAttachment> attachments(String json) {
        return read(json, ATTACHMENTS);
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

    private void commitPersistedContext(
            AiConversationGeneration generation,
            AiConversationGenerationPayload payload,
            long messageId,
            AiConversationSettlementCommand settlement) {
        if (payload.getContextGeneration() == null
                || payload.getEphemeralOrdinal() == null
                || messageId <= 0L
                || settlement == null) {
            invalidateContext(generation);
            return;
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
                    return;
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
    }

    private void invalidateContext(AiConversationGeneration generation) {
        try {
            contextStore.invalidate(idCodec.encode(generation.getConversationId()));
        } catch (RuntimeException ignoredFailure) {
            // PostgreSQL 消息和资金已经提交，Redis 删除失败只能由绝对 TTL 收敛，禁止反向回滚结算。
        }
    }
}
