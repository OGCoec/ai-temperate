package com.example.temperate.service.user.aiconversation.compaction.model.impl;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.availability.AiModelAvailabilityService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.user.aiconversation.compaction.model.AiConversationCompactionModelCatalog;
import com.example.temperate.service.user.aiconversation.compaction.model.AiConversationCompactionModelRef;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 复用启用模型加密快照生成压缩候选集合，并以单次数据库批量查询排除缓存中的停用旧值。
 */
@Service
public final class AiConversationCompactionModelCatalogImpl
        implements AiConversationCompactionModelCatalog {

    private final AiModelCacheService cacheService;
    private final AiModelAvailabilityService availabilityService;

    public AiConversationCompactionModelCatalogImpl(
            AiModelCacheService cacheService,
            AiModelAvailabilityService availabilityService) {
        this.cacheService = Objects.requireNonNull(cacheService);
        this.availabilityService = Objects.requireNonNull(availabilityService);
    }

    @Override
    public List<AiConversationCompactionModelRef> enabledModels() {
        AiModelCacheSnapshot snapshot = cacheService.getOrLoadEnabledSnapshot();
        if (snapshot.models().isEmpty()) {
            return List.of();
        }
        List<Long> candidateIds = snapshot.models().stream()
                .map(AiModelCacheEntry::id)
                .toList();
        Set<Long> enabledIds = availabilityService.findEnabledIds(candidateIds);
        if (enabledIds.isEmpty()) {
            return List.of();
        }
        // 压缩免费但仍会真实调用上游，只允许已实现供应商且具有 Chat 能力的模型进入候选集合。
        return snapshot.models().stream()
                .filter(model -> enabledIds.contains(model.id()))
                .filter(model -> model.capabilities().contains(
                        AiModelCapabilityCode.CHAT_COMPLETIONS))
                .flatMap(model -> compactionRef(model).stream())
                .toList();
    }

    private static java.util.Optional<AiConversationCompactionModelRef> compactionRef(
            AiModelCacheEntry model) {
        try {
            return java.util.Optional.of(new AiConversationCompactionModelRef(
                    model.id(),
                    AiModelProvider.fromVendor(model.vendor()),
                    model.modelName()));
        } catch (AiConversationException unsupported) {
            return java.util.Optional.empty();
        }
    }
}
