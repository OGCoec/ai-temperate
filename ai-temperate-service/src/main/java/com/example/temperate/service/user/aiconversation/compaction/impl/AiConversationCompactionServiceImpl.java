package com.example.temperate.service.user.aiconversation.compaction.impl;

import com.example.temperate.mapper.ai.AiConversationMapper;
import com.example.temperate.mapper.ai.AiConversationMessageMapper;
import com.example.temperate.model.ai.entity.AiConversation;
import com.example.temperate.model.ai.entity.AiConversationMessage;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionPersistenceService;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionService;
import com.example.temperate.service.user.aiconversation.compaction.model.AiConversationCompactionModelRef;
import com.example.temperate.service.user.aiconversation.compaction.model.AiConversationCompactionModelSelector;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextStore;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextSnapshot;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextWriteOutcome;
import com.example.temperate.service.user.aiconversation.context.AiConversationTurn;
import com.example.temperate.service.user.aiconversation.context.AiConversationTurnState;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLease;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLeaseService;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLeaseType;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelClient;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 在固定消息截止点上生成平台付费的持久化摘要，并以数据库 CAS 后置换 Redis 的旧持久化尾部。
 *
 * <p>持久压缩只读取 PostgreSQL 完整消息，临时压缩只读取 Redis 中由用户明确停止的 INTERRUPTED 轮次；
 * 队列满或单飞租约冲突只放弃本轮派生任务，下次请求仍会重新检测，不影响 PostgreSQL 权威历史。</p>
 */
@Service
public final class AiConversationCompactionServiceImpl
        implements AiConversationCompactionService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AiConversationCompactionServiceImpl.class);

    private final AiConversationMapper conversationMapper;
    private final AiConversationMessageMapper messageMapper;
    private final AiConversationCompactionPersistenceService persistenceService;
    private final AiConversationContextStore contextStore;
    private final AiConversationLeaseService leaseService;
    private final AiConversationModelClient modelClient;
    private final AiConversationCompactionModelSelector modelSelector;
    private final AiConversationProperties conversationProperties;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final AiConversationMetrics metrics;

    public AiConversationCompactionServiceImpl(
            AiConversationMapper conversationMapper,
            AiConversationMessageMapper messageMapper,
            AiConversationCompactionPersistenceService persistenceService,
            AiConversationContextStore contextStore,
            AiConversationLeaseService leaseService,
            AiConversationModelClient modelClient,
            AiConversationCompactionModelSelector modelSelector,
            AiConversationProperties conversationProperties,
            ObjectMapper objectMapper,
            @Qualifier("aiConversationCompactionExecutor") Executor executor,
            AiConversationMetrics metrics) {
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.persistenceService = Objects.requireNonNull(persistenceService);
        this.contextStore = Objects.requireNonNull(contextStore);
        this.leaseService = Objects.requireNonNull(leaseService);
        this.modelClient = Objects.requireNonNull(modelClient);
        this.modelSelector = Objects.requireNonNull(modelSelector);
        this.conversationProperties =
                Objects.requireNonNull(conversationProperties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.executor = Objects.requireNonNull(executor);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public void schedule(
            byte[] conversationId,
            String conversationPublicId,
            String cacheGeneration,
            long cutoffMessageId) {
        byte[] safeId = conversationId.clone();
        try {
            executor.execute(() -> compactDurableAtCutoff(
                    safeId,
                    conversationPublicId,
                    cacheGeneration,
                    cutoffMessageId));
        } catch (RejectedExecutionException exception) {
            metrics.compaction("persistent", "skipped");
            LOGGER.warn(
                    "event=ai_conversation_compaction_skipped reason=queue_full");
        }
    }

    @Override
    public void scheduleEphemeral(
            String conversationPublicId,
            String cacheGeneration) {
        try {
            executor.execute(() -> compactEphemeral(
                    conversationPublicId,
                    cacheGeneration,
                    false));
        } catch (RejectedExecutionException exception) {
            metrics.compaction("ephemeral", "skipped");
            LOGGER.warn(
                    "event=ai_conversation_ephemeral_compaction_skipped reason=queue_full");
        }
    }

    @Override
    public boolean compactSynchronously(
            byte[] conversationId,
            String conversationPublicId,
            String cacheGeneration) {
        Long cutoff = messageMapper.findLatestPersistedMessageId(conversationId);
        if (cutoff == null) {
            return false;
        }
        return compactDurableAtCutoff(
                conversationId,
                conversationPublicId,
                cacheGeneration,
                cutoff);
    }

    @Override
    public boolean compactEphemeralSynchronously(
            String conversationPublicId,
            String cacheGeneration) {
        return compactEphemeral(conversationPublicId, cacheGeneration, true);
    }

    private boolean compactDurableAtCutoff(
            byte[] conversationId,
            String conversationPublicId,
            String cacheGeneration,
            long cutoffMessageId) {
        AiConversationLease lease = leaseService.tryAcquire(
                        conversationPublicId,
                        AiConversationLeaseType.COMPACTION)
                .orElse(null);
        if (lease == null) {
            metrics.compaction("persistent", "skipped");
            return false;
        }
        try {
            boolean compacted = compactUnderLease(
                    conversationId,
                    conversationPublicId,
                    cacheGeneration,
                    cutoffMessageId);
            metrics.compaction("persistent",
                    compacted ? "success" : "skipped");
            return compacted;
        } catch (RuntimeException exception) {
            metrics.compaction("persistent", "failed");
            LOGGER.warn(
                    "event=ai_conversation_compaction_failed category={}",
                    exception.getClass().getSimpleName());
            return false;
        } finally {
            releaseLeaseBestEffort(lease);
        }
    }

    private boolean compactUnderLease(
            byte[] conversationId,
            String conversationPublicId,
            String cacheGeneration,
            long cutoff) {
        AiConversation conversation = conversationMapper.findById(conversationId);
        if (conversation == null) {
            return false;
        }
        Long expectedCheckpoint = conversation.getLastCompactedMessageId();
        long cursor = expectedCheckpoint == null ? 0L : expectedCheckpoint;
        if (cutoff <= cursor) {
            return false;
        }
        // 同一压缩任务冻结一个启用模型，避免多页滚动摘要在管理员并发启停时跨模型漂移。
        AiConversationCompactionModelRef selectedModel =
                modelSelector.selectRequired(conversationPublicId);
        String rollingCompaction = conversation.getCompactedContextJson();
        boolean foundMessages = false;
        while (cursor < cutoff) {
            List<AiConversationMessage> page =
                    messageMapper.findCompactionRange(
                            conversationId,
                            cursor,
                            cutoff,
                            conversationProperties.contextPageSize());
            if (page.isEmpty()) {
                break;
            }
            // 每页独立生成滚动摘要，禁止把全部历史再次聚合成一个无界模型请求。
            String pageSummary = modelClient.compact(
                    selectedModel.provider(),
                    selectedModel.modelName(),
                    compactionPrompt(rollingCompaction, page));
            cursor = page.get(page.size() - 1).getId();
            rollingCompaction = compactedJson(cursor, pageSummary);
            foundMessages = true;
            if (page.size() < conversationProperties.contextPageSize()) {
                break;
            }
        }
        if (!foundMessages || cursor < cutoff) {
            return false;
        }
        String compactedJson = rollingCompaction;
        if (!persistenceService.compareAndSet(
                conversationId,
                expectedCheckpoint,
                cutoff,
                compactedJson)) {
            return false;
        }
        if (cacheGeneration == null) {
            return true;
        }
        // 数据库 CAS 已提交后才替换缓存；缓存失败只损失加速层，后续会按新检查点安全重建。
        String generation = cacheGeneration;
        for (int attempt = 0; attempt < 3; attempt++) {
            AiConversationContextWriteOutcome outcome =
                    contextStore.replaceDurableCompaction(
                            conversationPublicId,
                            generation,
                            cutoff,
                            compactedJson);
            if (outcome == AiConversationContextWriteOutcome.APPLIED
                    || outcome == AiConversationContextWriteOutcome.UNAVAILABLE) {
                return true;
            }
            AiConversationContextSnapshot current =
                    contextStore.find(conversationPublicId).orElse(null);
            if (current == null) {
                return true;
            }
            generation = current.generation();
        }
        return true;
    }

    private boolean compactEphemeral(
            String conversationPublicId,
            String cacheGeneration,
            boolean force) {
        AiConversationLease lease = leaseService.tryAcquire(
                        conversationPublicId,
                        AiConversationLeaseType.COMPACTION)
                .orElse(null);
        if (lease == null) {
            metrics.compaction("ephemeral", "skipped");
            return false;
        }
        try {
            AiConversationContextSnapshot snapshot = contextStore
                    .find(conversationPublicId)
                    .filter(value -> value.generation().equals(cacheGeneration))
                    .orElse(null);
            if (snapshot == null
                    || (!force && snapshot.fieldCount()
                    < conversationProperties.compactionHashFieldThreshold())) {
                metrics.compaction("ephemeral", "skipped");
                return false;
            }
            List<AiConversationTurn> interrupted = snapshot.turns().stream()
                    .filter(turn -> turn.state()
                            == AiConversationTurnState.INTERRUPTED)
                    // 技术断线和系统失败草稿只用于诊断，不能通过摘要重新进入后续模型上下文。
                    .filter(AiConversationTurn::includedInPrompt)
                    // 单轮最多压缩 100 个临时轮次，保证删除字段数和模型输入都有明确上限。
                    .limit(100)
                    .toList();
            if (interrupted.isEmpty()) {
                metrics.compaction("ephemeral", "skipped");
                return false;
            }
            long cutoff = interrupted.stream()
                    .map(AiConversationTurn::ordinal)
                    .filter(Objects::nonNull)
                    .mapToLong(Long::longValue)
                    .max()
                    .orElse(0L);
            // 临时压缩同样在任务开始时冻结选择，管理员启停只影响后续新任务。
            AiConversationCompactionModelRef selectedModel =
                    modelSelector.selectRequired(conversationPublicId);
            String summary = modelClient.compact(
                    selectedModel.provider(),
                    selectedModel.modelName(),
                    ephemeralCompactionPrompt(
                            snapshot.ephemeralCompactionJson(),
                            interrupted));
            String compactedJson = ephemeralCompactedJson(cutoff, summary);
            List<Long> compactedOrdinals = interrupted.stream()
                    .map(AiConversationTurn::ordinal)
                    .filter(Objects::nonNull)
                    .toList();
            boolean compacted = contextStore.replaceEphemeralCompaction(
                    conversationPublicId,
                    cacheGeneration,
                    compactedJson,
                    cutoff,
                    compactedOrdinals)
                    == AiConversationContextWriteOutcome.APPLIED;
            metrics.compaction("ephemeral",
                    compacted ? "success" : "conflict");
            return compacted;
        } catch (RuntimeException exception) {
            metrics.compaction("ephemeral", "failed");
            LOGGER.warn(
                    "event=ai_conversation_ephemeral_compaction_failed category={}",
                    exception.getClass().getSimpleName());
            return false;
        } finally {
            releaseLeaseBestEffort(lease);
        }
    }

    private void releaseLeaseBestEffort(AiConversationLease lease) {
        try {
            leaseService.release(lease);
        } catch (RuntimeException ignoredFailure) {
            // 压缩是派生任务，释放失败由租约绝对 TTL 收敛，不能覆盖已经提交的数据库检查点。
        }
    }

    private String compactionPrompt(
            String previousCompaction,
            List<AiConversationMessage> messages) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previousCompaction", previousCompaction);
        payload.put("messages", messages.stream()
                .map(message -> Map.of(
                        "messageId", Long.toString(message.getId()),
                        "userText", message.getContentText(),
                        "userAttachmentsJson", message.getContentAttachmentsJson(),
                        "assistantText", message.getQuestionTokens(),
                        "assistantAttachmentsJson", message.getResponseAttachmentsJson()))
                .toList());
        return json(payload);
    }

    private String compactedJson(long cutoff, String summaryText) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("throughMessageId", Long.toString(cutoff));
        payload.put("summaryText", Objects.requireNonNullElse(summaryText, ""));
        payload.put("facts", List.of());
        payload.put("decisions", List.of());
        payload.put("openItems", List.of());
        return json(payload);
    }

    private String ephemeralCompactionPrompt(
            String previousCompaction,
            List<AiConversationTurn> turns) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previousCompaction", previousCompaction);
        payload.put("interruptedTurns", turns.stream()
                .map(turn -> Map.of(
                        "ordinal", Long.toString(turn.ordinal()),
                        "userText", turn.user().text(),
                        "userAttachments", turn.user().attachments(),
                        "assistantText", turn.assistant().text(),
                        "assistantAttachments", turn.assistant().attachments()))
                .toList());
        return json(payload);
    }

    private String ephemeralCompactedJson(
            long cutoff,
            String summaryText) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("throughEphemeralOrdinal", Long.toString(cutoff));
        payload.put("summaryText", Objects.requireNonNullElse(summaryText, ""));
        return json(payload);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "AI conversation compaction JSON serialization failed.",
                    exception);
        }
    }
}
