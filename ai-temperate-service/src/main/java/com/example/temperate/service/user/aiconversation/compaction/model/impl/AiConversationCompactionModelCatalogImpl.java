package com.example.temperate.service.user.aiconversation.compaction.model.impl;

import com.example.temperate.service.admin.aimodel.availability.AiModelAvailabilityService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.user.aiconversation.compaction.model.AiConversationCompactionModelCatalog;
import com.example.temperate.service.user.aiconversation.compaction.model.AiConversationCompactionModelRef;
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
        // 管理员启用状态就是压缩资格，不按能力、厂商、标签或套餐执行二次过滤。
        return snapshot.models().stream()
                .filter(model -> enabledIds.contains(model.id()))
                .map(model -> new AiConversationCompactionModelRef(
                        model.id(), model.modelName()))
                .toList();
    }
}
