package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.temperate.service.user.aiconversation.generation.worker.impl.AiConversationGenerationActiveRegistryImpl;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

/**
 * 验证取消早于模型订阅、重复 Owner 和 Registry 清理时仍保持单一上游取消句柄。
 */
class AiConversationGenerationActiveRegistryTest {

    @Test
    void cancellationBeforeSubscriptionDisposesHandleAsSoonAsItRegisters() {
        AiConversationGenerationActiveRegistryImpl registry =
                new AiConversationGenerationActiveRegistryImpl();
        Disposable handle = mock(Disposable.class);

        assertThat(registry.cancel("generation-a")).isFalse();
        registry.register("generation-a", handle);

        verify(handle).dispose();
        assertThat(registry.isActive("generation-a")).isTrue();
        registry.remove("generation-a", handle);
        assertThat(registry.isActive("generation-a")).isFalse();
    }

    @Test
    void duplicateOwnerCannotReplaceTheOriginalCancellationHandle() {
        AiConversationGenerationActiveRegistryImpl registry =
                new AiConversationGenerationActiveRegistryImpl();
        registry.register("generation-a", mock(Disposable.class));

        assertThatThrownBy(() -> registry.register(
                "generation-a", mock(Disposable.class)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelledBeforeStartCanClearPendingMarkerWithoutCreatingAHandle() {
        AiConversationGenerationActiveRegistryImpl registry =
                new AiConversationGenerationActiveRegistryImpl();
        Disposable handle = mock(Disposable.class);

        registry.cancel("generation-a");
        registry.clear("generation-a");
        registry.register("generation-a", handle);

        assertThat(registry.isActive("generation-a")).isTrue();
    }
}
