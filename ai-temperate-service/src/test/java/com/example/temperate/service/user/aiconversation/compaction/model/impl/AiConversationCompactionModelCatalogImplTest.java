package com.example.temperate.service.user.aiconversation.compaction.model.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.availability.AiModelAvailabilityService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.user.aiconversation.compaction.model.AiConversationCompactionModelRef;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证动态压缩模型目录复用启用快照，并通过一次批量强确认剔除已停用模型。
 */
@ExtendWith(MockitoExtension.class)
final class AiConversationCompactionModelCatalogImplTest {

    @Mock
    private AiModelCacheService cacheService;
    @Mock
    private AiModelAvailabilityService availabilityService;

    @Test
    void returnsOnlyEnabledChatModelsForImplementedProviders() {
        AiModelCacheSnapshot snapshot = new AiModelCacheSnapshot(
                AiModelCacheSnapshot.CURRENT_SCHEMA_VERSION,
                List.of(
                        model(11L, "text-model", "openai",
                                AiModelCapabilityCode.CHAT_COMPLETIONS),
                        model(12L, "disabled-after-cache", "xai",
                                AiModelCapabilityCode.CHAT_COMPLETIONS),
                        model(13L, "media-model", "openai",
                                AiModelCapabilityCode.VIDEO_INPUT),
                        model(14L, "xai-text", "xai",
                                AiModelCapabilityCode.CHAT_COMPLETIONS),
                        model(15L, "unsupported", "anthropic",
                                AiModelCapabilityCode.CHAT_COMPLETIONS)));
        when(cacheService.getOrLoadEnabledSnapshot()).thenReturn(snapshot);
        when(availabilityService.findEnabledIds(List.of(11L, 12L, 13L, 14L, 15L)))
                .thenReturn(Set.of(11L, 13L, 14L, 15L));
        AiConversationCompactionModelCatalogImpl catalog =
                new AiConversationCompactionModelCatalogImpl(
                        cacheService, availabilityService);

        List<AiConversationCompactionModelRef> result = catalog.enabledModels();

        assertThat(result).containsExactly(
                new AiConversationCompactionModelRef(
                        11L, AiModelProvider.OPENAI, "text-model"),
                new AiConversationCompactionModelRef(
                        14L, AiModelProvider.XAI, "xai-text"));
        verify(availabilityService).findEnabledIds(
                List.of(11L, 12L, 13L, 14L, 15L));
        assertThatThrownBy(() -> result.add(
                new AiConversationCompactionModelRef(14L, "mutated")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptySnapshotSkipsAvailabilityQuery() {
        when(cacheService.getOrLoadEnabledSnapshot()).thenReturn(
                new AiModelCacheSnapshot(
                        AiModelCacheSnapshot.CURRENT_SCHEMA_VERSION,
                        List.of()));
        AiConversationCompactionModelCatalogImpl catalog =
                new AiConversationCompactionModelCatalogImpl(
                        cacheService, availabilityService);

        assertThat(catalog.enabledModels()).isEmpty();
        verifyNoInteractions(availabilityService);
    }

    private static AiModelCacheEntry model(
            long id,
            String modelName,
            String vendor,
            AiModelCapabilityCode capability) {
        return new AiModelCacheEntry(
                id,
                modelName,
                vendor,
                "test model",
                null,
                List.of(),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                128_000L,
                16_000L,
                List.of(capability));
    }
}
