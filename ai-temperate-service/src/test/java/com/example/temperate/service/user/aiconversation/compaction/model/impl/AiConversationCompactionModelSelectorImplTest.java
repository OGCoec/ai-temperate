package com.example.temperate.service.user.aiconversation.compaction.model.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.compaction.model.AiConversationCompactionModelCatalog;
import com.example.temperate.service.user.aiconversation.compaction.model.AiConversationCompactionModelRef;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 验证压缩模型选择使用无状态一致性哈希，不依赖候选集合顺序或共享轮询游标。
 */
final class AiConversationCompactionModelSelectorImplTest {

    private static final List<AiConversationCompactionModelRef> MODELS = List.of(
            new AiConversationCompactionModelRef(11L, "model-a"),
            new AiConversationCompactionModelRef(12L, "model-b"),
            new AiConversationCompactionModelRef(13L, "model-c"));

    @Test
    void selectionIsStableAndIndependentOfCandidateOrder() {
        AiConversationCompactionModelSelectorImpl forward = selector(MODELS);
        AiConversationCompactionModelSelectorImpl reverse = selector(List.of(
                MODELS.get(2), MODELS.get(1), MODELS.get(0)));

        AiConversationCompactionModelRef first =
                forward.selectRequired("AZ-45YVzAQGhxtYdYAxxQg");

        assertThat(forward.selectRequired("AZ-45YVzAQGhxtYdYAxxQg"))
                .isEqualTo(first);
        assertThat(reverse.selectRequired("AZ-45YVzAQGhxtYdYAxxQg"))
                .isEqualTo(first);
    }

    @Test
    void differentConversationsCanDistributeAcrossEnabledModels() {
        AiConversationCompactionModelSelectorImpl selector = selector(MODELS);
        Set<Long> selectedIds = new HashSet<>();

        for (int index = 0; index < 100; index++) {
            selectedIds.add(selector.selectRequired(
                    "conversation-" + index).id());
        }

        assertThat(selectedIds).hasSizeGreaterThan(1);
    }

    @Test
    void emptyCatalogReturnsControlledUpstreamUnavailableError() {
        AiConversationCompactionModelSelectorImpl selector = selector(List.of());

        assertThatThrownBy(() -> selector.selectRequired("conversation-1"))
                .isInstanceOfSatisfying(
                        AiConversationException.class,
                        exception -> {
                            assertThat(exception.code()).isEqualTo(
                                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE);
                            assertThat(exception.retryable()).isTrue();
                        });
    }

    private static AiConversationCompactionModelSelectorImpl selector(
            List<AiConversationCompactionModelRef> models) {
        AiConversationCompactionModelCatalog catalog = () -> models;
        return new AiConversationCompactionModelSelectorImpl(catalog);
    }
}
