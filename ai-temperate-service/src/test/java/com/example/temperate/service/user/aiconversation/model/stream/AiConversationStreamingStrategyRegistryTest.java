package com.example.temperate.service.user.aiconversation.model.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 验证流式策略注册表按稳定协议枚举选择实现，并在重复或缺失注册时启动失败。
 */
final class AiConversationStreamingStrategyRegistryTest {

    @Test
    void selectsEveryProviderProtocolPairAndRejectsDuplicates() {
        StubStrategy openAiChat = strategy(AiModelProvider.OPENAI,
                AiConversationStreamingProtocol.CHAT_COMPLETIONS,
                AiConversationMeteringBasis.TOKEN);
        StubStrategy openAiResponses = strategy(AiModelProvider.OPENAI,
                AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH,
                AiConversationMeteringBasis.TOKEN);
        StubStrategy openAiImages = strategy(AiModelProvider.OPENAI,
                AiConversationStreamingProtocol.IMAGES_GENERATION,
                AiConversationMeteringBasis.TOKEN);
        StubStrategy xaiChat = strategy(AiModelProvider.XAI,
                AiConversationStreamingProtocol.CHAT_COMPLETIONS,
                AiConversationMeteringBasis.TOKEN);
        StubStrategy xaiResponses = strategy(AiModelProvider.XAI,
                AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH,
                AiConversationMeteringBasis.TOKEN);
		StubStrategy xaiImages = strategy(AiModelProvider.XAI,
				AiConversationStreamingProtocol.IMAGES_GENERATION,
				AiConversationMeteringBasis.PROVIDER_COST_TICKS);
		StubStrategy xaiVideos = strategy(AiModelProvider.XAI,
				AiConversationStreamingProtocol.VIDEOS_GENERATION,
				AiConversationMeteringBasis.PROVIDER_COST_TICKS);
        StubStrategy anthropicChat = strategy(AiModelProvider.ANTHROPIC,
                AiConversationStreamingProtocol.CHAT_COMPLETIONS,
                AiConversationMeteringBasis.TOKEN);
        StubStrategy anthropicSearch = strategy(AiModelProvider.ANTHROPIC,
                AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH,
                AiConversationMeteringBasis.TOKEN);
        StubStrategy googleChat = strategy(AiModelProvider.GOOGLE,
                AiConversationStreamingProtocol.CHAT_COMPLETIONS,
                AiConversationMeteringBasis.TOKEN);
        StubStrategy googleSearch = strategy(AiModelProvider.GOOGLE,
                AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH,
                AiConversationMeteringBasis.TOKEN);
        StubStrategy googleImages = strategy(AiModelProvider.GOOGLE,
                AiConversationStreamingProtocol.IMAGES_GENERATION,
                AiConversationMeteringBasis.TOKEN);
        AiConversationStreamingStrategyRegistry registry =
                new AiConversationStreamingStrategyRegistry(Map.ofEntries(
                        Map.entry("openAiChat", openAiChat),
                        Map.entry("openAiResponses", openAiResponses),
                        Map.entry("openAiImages", openAiImages),
                        Map.entry("xaiChat", xaiChat),
                        Map.entry("xaiResponses", xaiResponses),
						Map.entry("xaiImages", xaiImages),
						Map.entry("xaiVideos", xaiVideos),
                        Map.entry("anthropicChat", anthropicChat),
                        Map.entry("anthropicSearch", anthropicSearch),
                        Map.entry("googleChat", googleChat),
                        Map.entry("googleSearch", googleSearch),
                        Map.entry("googleImages", googleImages)));

        assertThat(registry.getRequired(AiModelProvider.OPENAI,
                AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH))
                .isSameAs(openAiResponses);
		assertThat(registry.getRequired(AiModelProvider.XAI,
				AiConversationStreamingProtocol.IMAGES_GENERATION))
				.isSameAs(xaiImages);
		assertThat(registry.getRequired(AiModelProvider.XAI,
				AiConversationStreamingProtocol.VIDEOS_GENERATION))
				.isSameAs(xaiVideos);
        assertThat(registry.getRequired(AiModelProvider.ANTHROPIC,
                AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH))
                .isSameAs(anthropicSearch);
        assertThat(registry.getRequired(AiModelProvider.GOOGLE,
                AiConversationStreamingProtocol.IMAGES_GENERATION))
                .isSameAs(googleImages);
        assertThatThrownBy(() -> new AiConversationStreamingStrategyRegistry(
                Map.of(
                        "first", openAiChat,
                        "second", strategy(AiModelProvider.OPENAI,
                                openAiChat.protocol(),
                                AiConversationMeteringBasis.TOKEN))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void rejectsMissingProviderProtocolPairAtStartup() {
        assertThatThrownBy(() -> new AiConversationStreamingStrategyRegistry(
                Map.of(
                        "openAiChat", strategy(
                                AiModelProvider.OPENAI,
                                AiConversationStreamingProtocol.CHAT_COMPLETIONS,
                                AiConversationMeteringBasis.TOKEN))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing AI conversation streaming strategy");
    }

    @Test
    void rejectsStrategyOutsideDeclaredSupportMatrix() {
        java.util.HashMap<String, AiConversationStreamingStrategy> strategies =
                new java.util.HashMap<>(completeStrategies());
        strategies.put("anthropicImages", strategy(
                AiModelProvider.ANTHROPIC,
                AiConversationStreamingProtocol.IMAGES_GENERATION,
                AiConversationMeteringBasis.TOKEN));

        assertThatThrownBy(() -> new AiConversationStreamingStrategyRegistry(
                strategies))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported AI conversation streaming strategy");
    }

    private static Map<String, AiConversationStreamingStrategy>
            completeStrategies() {
        java.util.HashMap<String, AiConversationStreamingStrategy> values =
                new java.util.HashMap<>();
		put(values, "openAi", AiModelProvider.OPENAI,
				AiConversationMeteringBasis.TOKEN,
				AiConversationStreamingProtocol.CHAT_COMPLETIONS,
				AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH,
				AiConversationStreamingProtocol.IMAGES_GENERATION);
		put(values, "xai", AiModelProvider.XAI,
				AiConversationMeteringBasis.TOKEN,
				AiConversationStreamingProtocol.CHAT_COMPLETIONS,
				AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH);
		values.put("xaiImages", strategy(AiModelProvider.XAI,
				AiConversationStreamingProtocol.IMAGES_GENERATION,
				AiConversationMeteringBasis.PROVIDER_COST_TICKS));
		values.put("xaiVideos", strategy(AiModelProvider.XAI,
				AiConversationStreamingProtocol.VIDEOS_GENERATION,
				AiConversationMeteringBasis.PROVIDER_COST_TICKS));
        put(values, "anthropic", AiModelProvider.ANTHROPIC,
                AiConversationMeteringBasis.TOKEN,
                AiConversationStreamingProtocol.CHAT_COMPLETIONS,
                AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH);
		put(values, "google", AiModelProvider.GOOGLE,
				AiConversationMeteringBasis.TOKEN,
				AiConversationStreamingProtocol.CHAT_COMPLETIONS,
				AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH,
				AiConversationStreamingProtocol.IMAGES_GENERATION);
        return Map.copyOf(values);
    }

    private static void put(
            Map<String, AiConversationStreamingStrategy> values,
            String prefix,
            AiModelProvider provider,
            AiConversationMeteringBasis basis,
            AiConversationStreamingProtocol... protocols) {
        AiConversationStreamingProtocol[] selected = protocols.length == 0
                ? AiConversationStreamingProtocol.values()
                : protocols;
        for (AiConversationStreamingProtocol protocol : selected) {
            if (provider == AiModelProvider.XAI
                    && protocol == AiConversationStreamingProtocol.IMAGES_GENERATION) {
                continue;
            }
            values.put(prefix + protocol.name(), strategy(provider, protocol, basis));
        }
    }

    private static StubStrategy strategy(
            AiModelProvider provider,
            AiConversationStreamingProtocol protocol,
            AiConversationMeteringBasis meteringBasis) {
        return new StubStrategy(provider, protocol, meteringBasis);
    }

    private record StubStrategy(
            AiModelProvider provider,
            AiConversationStreamingProtocol protocol,
            AiConversationMeteringBasis meteringBasis)
            implements AiConversationStreamingStrategy {

        @Override
        public Flux<AiConversationModelEvent> stream(
                AiConversationStreamingRequest request) {
            return Flux.empty();
        }
    }
}
