package com.example.temperate.service.user.aiconversation.context.usage.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiConversationMapper;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionOperation;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionStateStore;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionStatus;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextService;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextSnapshot;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsage;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsageEvaluation;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsagePolicy;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsageService;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 从 Redis v2 快照读取累计 Token，并按当前启用模型重新计算百分比和绝对容量状态。
 */
@Service
public final class AiConversationContextUsageServiceImpl
        implements AiConversationContextUsageService {

    private final AiConversationMapper conversationMapper;
    private final AiConversationContextService contextService;
    private final AiModelCacheService modelCacheService;
    private final PublicIdCodec publicIdCodec;
    private final AiConversationContextUsagePolicy usagePolicy;
    private final AiConversationCompactionStateStore stateStore;
    private final AiConversationProperties properties;

    public AiConversationContextUsageServiceImpl(
            AiConversationMapper conversationMapper,
            AiConversationContextService contextService,
            AiModelCacheService modelCacheService,
            PublicIdCodec publicIdCodec,
            AiConversationContextUsagePolicy usagePolicy,
            AiConversationCompactionStateStore stateStore,
            AiConversationProperties properties) {
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.contextService = Objects.requireNonNull(contextService);
        this.modelCacheService = Objects.requireNonNull(modelCacheService);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.usagePolicy = Objects.requireNonNull(usagePolicy);
        this.stateStore = Objects.requireNonNull(stateStore);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public AiConversationContextUsage getOwned(
            long userId,
            byte[] conversationId,
            String conversationPublicId,
            String modelPublicId) {
        if (conversationMapper.findActiveOwned(conversationId, userId) == null) {
            throw notFound();
        }
        return get(conversationId, conversationPublicId, modelPublicId);
    }

    @Override
    public AiConversationContextUsage get(
            byte[] conversationId,
            String conversationPublicId,
            String modelPublicId) {
        return usage(
                conversationId,
                conversationPublicId,
                requiredModel(publicIdCodec.decode(modelPublicId)));
    }

    @Override
    public AiConversationContextUsage get(
            byte[] conversationId,
            String conversationPublicId,
            long modelId) {
        return usage(
                conversationId,
                conversationPublicId,
                requiredModel(modelId));
    }

    private AiConversationContextUsage usage(
            byte[] conversationId,
            String conversationPublicId,
            AiModelCacheEntry model) {
        AiConversationContextSnapshot snapshot = contextService.load(
                conversationId, conversationPublicId);
        AiConversationContextUsageEvaluation evaluation = usagePolicy.evaluate(
                snapshot.estimatedContextTokens(),
                snapshot.estimatedContextTokens(),
                model.contextWindowTokens(),
                model.maxOutputTokens());
        AiConversationCompactionOperation operation = stateStore
                .find(conversationPublicId)
                .orElse(AiConversationCompactionOperation.idle(0L));
        if (operation.status().terminal()
                && operation.contextRevision() != snapshot.contextRevision()) {
            // 终态只描述它声明时的上下文版本；新版本不得继续显示旧任务正在影响当前快照。
            operation = AiConversationCompactionOperation.idle(
                    operation.eventRevision());
        }
        AiConversationCompactionStatus status = operation.status();
        return new AiConversationContextUsage(
                conversationPublicId,
                publicIdCodec.encode(model.id()),
                snapshot.estimatedContextTokens(),
                toK(snapshot.estimatedContextTokens()),
                model.contextWindowTokens(),
                toK(model.contextWindowTokens()),
                evaluation.usagePercent(),
                properties.preCompactionPercent(),
                evaluation.thresholdReached(),
                evaluation.hardLimitExceeded(),
                snapshot.contextRevision(),
                status.name(),
                operation.operationPublicId(),
                snapshot.updatedAt());
    }

    private AiModelCacheEntry requiredModel(long modelId) {
        return modelCacheService.getOrLoadEnabledSnapshot().models().stream()
                .filter(candidate -> candidate.id() == modelId)
                .findFirst()
                .orElseThrow(() -> new AiConversationException(
                        AiConversationErrorCode.AI_MODEL_NOT_AVAILABLE,
                        "所选模型当前不可用",
                        true));
    }

    private static long toK(long tokens) {
        return tokens == 0L
                ? 0L
                : Math.floorDiv(Math.addExact(tokens, 999L), 1_000L);
    }

    private static AiConversationException notFound() {
        return new AiConversationException(
                AiConversationErrorCode.AI_CONVERSATION_NOT_FOUND,
                "会话不存在或不可用",
                false);
    }
}
