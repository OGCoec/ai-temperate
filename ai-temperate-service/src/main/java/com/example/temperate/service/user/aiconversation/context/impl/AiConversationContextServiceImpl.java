package com.example.temperate.service.user.aiconversation.context.impl;

import com.example.temperate.mapper.ai.AiConversationMapper;
import com.example.temperate.mapper.ai.AiConversationMessageMapper;
import com.example.temperate.model.ai.entity.AiConversation;
import com.example.temperate.model.ai.entity.AiConversationMessage;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextService;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextSnapshot;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextStore;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextWriteOutcome;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.context.AiConversationTokenEstimator;
import com.example.temperate.service.user.aiconversation.context.AiConversationTurn;
import com.example.temperate.service.user.aiconversation.context.AiConversationTurnState;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 以 PostgreSQL 为持久化历史权威、Redis 为三天加速层，按固定顺序组装不可变 Prompt 并执行容量预算。
 *
 * <p>缓存缺失时只执行有界批量读取并回填一次；Redis-only 中断内容无法从数据库恢复，过期丢失属于明确语义。</p>
 */
@Service
public final class AiConversationContextServiceImpl
        implements AiConversationContextService {

    private static final TypeReference<List<AiConversationAttachment>> ATTACHMENT_LIST =
            new TypeReference<>() { };

    private final AiConversationMapper conversationMapper;
    private final AiConversationMessageMapper messageMapper;
    private final AiConversationContextStore contextStore;
    private final AiConversationTokenEstimator tokenEstimator;
    private final AiConversationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AiConversationMetrics metrics;

    public AiConversationContextServiceImpl(
            AiConversationMapper conversationMapper,
            AiConversationMessageMapper messageMapper,
            AiConversationContextStore contextStore,
            AiConversationTokenEstimator tokenEstimator,
            AiConversationProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            AiConversationMetrics metrics) {
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.contextStore = Objects.requireNonNull(contextStore);
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator);
        this.properties = Objects.requireNonNull(properties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public AiConversationPromptSnapshot prepareNew(
            AiModelCacheEntry model,
            AiConversationContent currentInput) {
        long estimated = tokenEstimator.estimate(
                properties.systemPrompt(),
                null,
                null,
                List.of(),
                currentInput);
        verifyBudget(model, estimated);
        return new AiConversationPromptSnapshot(
                properties.systemPrompt(),
                null,
                null,
                List.of(),
                currentInput,
                null,
                estimated,
                reachesCompactionThreshold(model, estimated));
    }

    @Override
    public AiConversationPromptSnapshot prepare(
            byte[] conversationId,
            String conversationPublicId,
            AiModelCacheEntry model,
            AiConversationContent currentInput) {
        AiConversationContextSnapshot snapshot = loadSnapshot(
                conversationId, conversationPublicId);
        long estimated = tokenEstimator.estimate(
                properties.systemPrompt(),
                snapshot.durableCompactionJson(),
                snapshot.ephemeralCompactionJson(),
                snapshot.turns(),
                currentInput);
        verifyBudget(model, estimated);
        return new AiConversationPromptSnapshot(
                properties.systemPrompt(),
                snapshot.durableCompactionJson(),
                snapshot.ephemeralCompactionJson(),
                snapshot.turns(),
                currentInput,
                snapshot.generation(),
                estimated,
                reachesCompactionThreshold(model, estimated)
                        || snapshot.fieldCount()
                        >= properties.compactionHashFieldThreshold());
    }

    private void verifyBudget(AiModelCacheEntry model, long estimated) {
        if (model.contextWindowTokens() <= 0L
                || model.maxOutputTokens() <= 0L) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_MODEL_LIMITS_MISSING,
                    "模型缺少上下文窗口或最大输出限制",
                    false);
        }
        long budget = Math.floorDiv(
                Math.multiplyExact(
                        model.contextWindowTokens(),
                        properties.preCompactionPercent()),
                100L);
        if (Math.addExact(estimated, model.maxOutputTokens()) > budget) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_CONTEXT_TOO_LARGE,
                    "当前会话超过模型上下文限制，需要先完成压缩",
                    false);
        }
    }

    private boolean reachesCompactionThreshold(
            AiModelCacheEntry model, long estimated) {
        long threshold = Math.floorDiv(
                Math.multiplyExact(
                        model.contextWindowTokens(),
                        properties.preCompactionPercent()),
                100L);
        return Math.addExact(estimated, model.maxOutputTokens()) >= threshold;
    }

    private AiConversationContextSnapshot rebuild(
            byte[] conversationId, String conversationPublicId) {
        AiConversation conversation = conversationMapper.findById(conversationId);
        if (conversation == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_CONVERSATION_NOT_FOUND,
                    "会话不存在或不可用",
                    false);
        }
        long checkpoint = conversation.getLastCompactedMessageId() == null
                ? 0L
                : conversation.getLastCompactedMessageId();
        List<AiConversationTurn> turns = new ArrayList<>();
        long cursor = checkpoint;
        while (true) {
            List<AiConversationMessage> page =
                    messageMapper.findAfterMessageId(
                            conversationId,
                            cursor,
                            properties.contextPageSize());
            for (AiConversationMessage message : page) {
                if (turns.size() >= Math.max(
                        1, properties.maxHashFields() / 3)) {
                    throw new AiConversationException(
                            AiConversationErrorCode.AI_CONTEXT_TOO_LARGE,
                            "会话尾部超过缓存安全边界，需要先完成压缩",
                            false);
                }
                turns.add(toTurn(message));
                cursor = message.getId();
            }
            if (page.size() < properties.contextPageSize()) {
                break;
            }
        }
        OffsetDateTime createdAt =
                clock.instant().atOffset(ZoneOffset.UTC);
        AiConversationContextSnapshot rebuilt =
                new AiConversationContextSnapshot(
                        1,
                        UUID.randomUUID().toString(),
                        createdAt,
                        createdAt.plus(properties.contextTtl()),
                        checkpoint,
                        cursor,
                        conversation.getCompactedContextJson(),
                        null,
                        turns,
                        0);
        AiConversationContextWriteOutcome outcome =
                contextStore.create(conversationPublicId, rebuilt);
        if (outcome == AiConversationContextWriteOutcome.GENERATION_MISMATCH) {
            metrics.context("generation_conflict");
            // 并发重建失败方必须采用胜出者的 generation，不能继续传播本地失效快照。
            return contextStore.find(conversationPublicId)
                    .orElseThrow(() -> new AiConversationException(
                            AiConversationErrorCode.AI_CONTEXT_CACHE_UNAVAILABLE,
                            "并发重建后的会话上下文尚不可读取",
                            true));
        }
        if (outcome == AiConversationContextWriteOutcome.UNAVAILABLE) {
            metrics.context("unavailable");
            throw new AiConversationException(
                    AiConversationErrorCode.AI_CONTEXT_CACHE_UNAVAILABLE,
                    "会话上下文重建后无法写入缓存",
                    true);
        }
        metrics.context("rebuild");
        return rebuilt;
    }

    private AiConversationContextSnapshot loadSnapshot(
            byte[] conversationId,
            String conversationPublicId) {
        AiConversationContextSnapshot cached;
        try {
            cached = contextStore.find(conversationPublicId).orElse(null);
        } catch (RuntimeException redisFailure) {
            metrics.context("unavailable");
            throw new AiConversationException(
                    AiConversationErrorCode.AI_CONTEXT_CACHE_UNAVAILABLE,
                    "会话上下文缓存当前不可用",
                    true);
        }
        if (cached == null) {
            metrics.context("miss");
            return rebuild(conversationId, conversationPublicId);
        }
        metrics.context("hit");
        AiConversation conversation = conversationMapper.findById(conversationId);
        if (conversation == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_CONVERSATION_NOT_FOUND,
                    "会话不存在或不可用",
                    false);
        }
        long databaseCheckpoint = conversation.getLastCompactedMessageId()
                == null ? 0L : conversation.getLastCompactedMessageId();
        if (databaseCheckpoint <= cached.lastCompactedMessageId()) {
            return cached;
        }
        // PostgreSQL 的压缩检查点已领先时只替换被覆盖的持久字段，保留全部 Redis-only 临时轮次。
        // generation 冲突必须采用胜出快照继续有限重试，不能静默返回已落后的检查点。
        AiConversationContextSnapshot candidate = cached;
        for (int attempt = 0; attempt < 3; attempt++) {
            AiConversationContextWriteOutcome repaired =
                    contextStore.replaceDurableCompaction(
                            conversationPublicId,
                            candidate.generation(),
                            databaseCheckpoint,
                            conversation.getCompactedContextJson());
            if (repaired == AiConversationContextWriteOutcome.APPLIED) {
                return contextStore.find(conversationPublicId)
                        .orElseThrow(() -> new AiConversationException(
                                AiConversationErrorCode
                                        .AI_CONTEXT_CACHE_UNAVAILABLE,
                                "修复后的会话上下文尚不可读取",
                                true));
            }
            if (repaired == AiConversationContextWriteOutcome.UNAVAILABLE) {
                metrics.context("unavailable");
                throw new AiConversationException(
                        AiConversationErrorCode.AI_CONTEXT_CACHE_UNAVAILABLE,
                        "会话上下文检查点修复失败",
                        true);
            }
            metrics.context("generation_conflict");
            candidate = contextStore.find(conversationPublicId)
                    .orElseThrow(() -> new AiConversationException(
                            AiConversationErrorCode.AI_CONTEXT_CACHE_UNAVAILABLE,
                            "并发更新后的会话上下文尚不可读取",
                            true));
            if (candidate.lastCompactedMessageId() >= databaseCheckpoint) {
                return candidate;
            }
        }
        throw new AiConversationException(
                AiConversationErrorCode.AI_CONTEXT_CACHE_UNAVAILABLE,
                "会话上下文检查点发生持续并发冲突",
                true);
    }

    private AiConversationTurn toTurn(AiConversationMessage message) {
        try {
            List<AiConversationAttachment> userAttachments = objectMapper.readValue(
                    message.getContentAttachmentsJson(), ATTACHMENT_LIST);
            List<AiConversationAttachment> assistantAttachments = objectMapper.readValue(
                    message.getResponseAttachmentsJson(), ATTACHMENT_LIST);
            return new AiConversationTurn(
                    Long.toString(message.getId()),
                    message.getId(),
                    null,
                    new AiConversationContent(
                            message.getContentText(), userAttachments),
                    new AiConversationContent(
                            message.getQuestionTokens(), assistantAttachments),
                    AiConversationTurnState.PERSISTED);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Persisted AI conversation message JSON is invalid.",
                    exception);
        }
    }
}
