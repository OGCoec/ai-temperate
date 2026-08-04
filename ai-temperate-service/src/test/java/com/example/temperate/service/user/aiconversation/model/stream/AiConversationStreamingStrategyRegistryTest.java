package com.example.temperate.service.user.aiconversation.model.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 验证流式策略注册表按稳定协议枚举选择实现，并在重复或缺失注册时启动失败。
 */
final class AiConversationStreamingStrategyRegistryTest {

    @Test
    void selectsEveryRegisteredProtocolAndRejectsDuplicates() {
        StubStrategy chat = new StubStrategy(
                AiConversationStreamingProtocol.CHAT_COMPLETIONS);
        StubStrategy responses = new StubStrategy(
                AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH);
        StubStrategy images = new StubStrategy(
                AiConversationStreamingProtocol.IMAGES_GENERATION);
        AiConversationStreamingStrategyRegistry registry =
                new AiConversationStreamingStrategyRegistry(Map.of(
                        "chat", chat,
                        "responses", responses,
                        "images", images));

        assertThat(registry.required(chat.protocol())).isSameAs(chat);
        assertThat(registry.required(responses.protocol())).isSameAs(responses);
        assertThat(registry.required(images.protocol())).isSameAs(images);
        assertThatThrownBy(() -> new AiConversationStreamingStrategyRegistry(
                Map.of(
                        "first", chat,
                        "second", new StubStrategy(chat.protocol()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    private record StubStrategy(AiConversationStreamingProtocol protocol)
            implements AiConversationStreamingStrategy {

        @Override
        public Flux<AiConversationModelEvent> stream(
                AiConversationStreamingRequest request) {
            return Flux.empty();
        }
    }
}
