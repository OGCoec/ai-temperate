package com.example.temperate.service.user.aiconversation.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.context.AiConversationInterruptionSource;
import com.example.temperate.service.user.aiconversation.response.impl.AiConversationDirectResponseActiveRegistryImpl;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 验证直接 SSE 活动流注册表只向当前所有者交付一次显式取消。
 */
final class AiConversationDirectResponseActiveRegistryTest {

    @Test
    void repeatedCancellationInvokesTheRegisteredHandleOnlyOnce() {
        AiConversationDirectResponseActiveRegistryImpl registry =
                new AiConversationDirectResponseActiveRegistryImpl();
        AtomicInteger cancellations = new AtomicInteger();
        AiConversationDirectResponseCancellationHandle handle = source -> {
            assertThat(source).isEqualTo(AiConversationInterruptionSource.USER_STOP);
            cancellations.incrementAndGet();
        };
        registry.register("request-key", handle);

        assertThat(registry.cancel(
                "request-key", AiConversationInterruptionSource.USER_STOP)).isTrue();
        assertThat(registry.cancel(
                "request-key", AiConversationInterruptionSource.USER_STOP)).isFalse();
        assertThat(cancellations).hasValue(1);
    }

    @Test
    void removingACompletedHandleCannotRemoveANewerOwner() {
        AiConversationDirectResponseActiveRegistryImpl registry =
                new AiConversationDirectResponseActiveRegistryImpl();
        AiConversationDirectResponseCancellationHandle first = ignored -> { };
        AiConversationDirectResponseCancellationHandle second = ignored -> { };
        registry.register("request-key", first);
        registry.remove("request-key", first);
        registry.register("request-key", second);

        registry.remove("request-key", first);

        assertThat(registry.isActive("request-key")).isTrue();
    }
}
