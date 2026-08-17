package com.example.temperate.service.user.apikey.model.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.model.ApiKeyModelDiscoveryException;
import com.example.temperate.service.user.apikey.model.ApiKeyModelDiscoveryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束公开模型发现只暴露当前 API Key 已授权、仍启用且可用于 Chat Completions 的模型。
 */
final class ApiKeyModelDiscoveryServiceImplTest {

    @Test
    void listsOnlyAuthorizedChatModelsInStableNameOrder() {
        ApiKeyModelDiscoveryService service = new ApiKeyModelDiscoveryServiceImpl(
                cacheService(List.of(
                        model(11L, "zeta-chat", LocalDate.of(2026, 8, 15),
                                AiModelCapabilityCode.CHAT_COMPLETIONS),
                        model(12L, "alpha-chat", LocalDate.of(2026, 8, 14),
                                AiModelCapabilityCode.CHAT_COMPLETIONS),
                        model(13L, "ungranted-chat", LocalDate.of(2026, 8, 13),
                                AiModelCapabilityCode.CHAT_COMPLETIONS),
                        model(14L, "granted-image", LocalDate.of(2026, 8, 12),
                                AiModelCapabilityCode.IMAGE_GENERATION))));

        List<ApiKeyModelDiscoveryService.AuthorizedModel> result = service.list(
                principal(Set.of(11L, 12L, 14L)));

        assertThat(result)
                .extracting(ApiKeyModelDiscoveryService.AuthorizedModel::modelName)
                .containsExactly("alpha-chat", "zeta-chat");
        assertThat(result)
                .extracting(ApiKeyModelDiscoveryService.AuthorizedModel::createdEpochSeconds)
                .containsExactly(
                        LocalDate.of(2026, 8, 14).atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC),
                        LocalDate.of(2026, 8, 15).atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC));
    }

    @Test
    void returnsEmptyListWhenAuthenticatedKeyHasNoActiveModelGrants() {
        ApiKeyModelDiscoveryService service = new ApiKeyModelDiscoveryServiceImpl(
                cacheService(List.of(model(11L, "chat", LocalDate.of(2026, 8, 15),
                        AiModelCapabilityCode.CHAT_COMPLETIONS))));

        assertThat(service.list(principal(Set.of()))).isEmpty();
    }

    @Test
    void failsClosedWhenEnabledModelSnapshotCannotBeLoaded() {
        AiModelCacheService unavailable = new AiModelCacheService() {
            @Override
            public Optional<AiModelCacheSnapshot> findEnabledSnapshot() {
                return Optional.empty();
            }

            @Override
            public AiModelCacheSnapshot getOrLoadEnabledSnapshot() {
                throw new IllegalStateException("Redis is unavailable");
            }

            @Override
            public void refreshEnabledSnapshot() {
                throw new UnsupportedOperationException("not used");
            }
        };
        ApiKeyModelDiscoveryService service = new ApiKeyModelDiscoveryServiceImpl(unavailable);

        assertThatThrownBy(() -> service.list(principal(Set.of(11L))))
                .isInstanceOf(ApiKeyModelDiscoveryException.class)
                .hasMessage("The model catalog is temporarily unavailable.");
    }

    private static ApiKeyPrincipal principal(Set<Long> modelIds) {
        return new ApiKeyPrincipal(1L, 2L, new byte[32], "A".repeat(43), modelIds);
    }

    private static AiModelCacheEntry model(
            long id,
            String name,
            LocalDate createdAt,
            AiModelCapabilityCode capability) {
        return new AiModelCacheEntry(
                id,
                name,
                createdAt.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond(),
                "openai",
                "test",
                null,
                List.of(),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                4_096,
                512,
                List.of(capability));
    }

    private static AiModelCacheService cacheService(List<AiModelCacheEntry> models) {
        AiModelCacheSnapshot snapshot = new AiModelCacheSnapshot(
                AiModelCacheSnapshot.CURRENT_SCHEMA_VERSION, models);
        return new AiModelCacheService() {
            @Override
            public Optional<AiModelCacheSnapshot> findEnabledSnapshot() {
                return Optional.of(snapshot);
            }

            @Override
            public AiModelCacheSnapshot getOrLoadEnabledSnapshot() {
                return snapshot;
            }

            @Override
            public void refreshEnabledSnapshot() {
                throw new UnsupportedOperationException("read-only fixture");
            }
        };
    }
}
