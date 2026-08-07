package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelRequest;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Anthropic Messages 普通对话和联网搜索各自只输出原生协议允许的字段。
 */
final class AnthropicMessagesRequestFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsAllFiveEffortLevels() {
        AnthropicMessagesRequestFactory factory =
                new AnthropicMessagesRequestFactory(objectMapper);

        assertThat(factory.create(request(AiConversationReasoningEffort.LOW))
                .path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(factory.create(request(AiConversationReasoningEffort.MEDIUM))
                .path("output_config").path("effort").asText()).isEqualTo("medium");
        assertThat(factory.create(request(AiConversationReasoningEffort.HIGH))
                .path("output_config").path("effort").asText()).isEqualTo("high");
        assertThat(factory.create(request(AiConversationReasoningEffort.EXTRA_HIGH))
                .path("output_config").path("effort").asText()).isEqualTo("xhigh");
        assertThat(factory.create(request(AiConversationReasoningEffort.ULTRA))
                .path("output_config").path("effort").asText()).isEqualTo("max");
    }

    @Test
    void buildsNativeSearchToolChoiceWithoutOpenAiFields() {
        AnthropicWebSearchRequestFactory factory =
                new AnthropicWebSearchRequestFactory(objectMapper);
        JsonNode auto = factory.create(searchRequest(AiConversationWebSearchMode.AUTO));
        JsonNode required = factory.create(searchRequest(
                AiConversationWebSearchMode.REQUIRED));

        assertThat(auto.path("tools").path(0).path("type").asText())
                .isEqualTo("web_search_20260318");
        assertThat(auto.path("tools").path(0).path("name").asText())
                .isEqualTo("web_search");
        assertThat(auto.path("tool_choice").path("type").asText())
                .isEqualTo("auto");
        assertThat(required.path("tool_choice").path("type").asText())
                .isEqualTo("tool");
        assertThat(required.path("tool_choice").path("name").asText())
                .isEqualTo("web_search");
        assertThat(required.toString())
                .doesNotContain("search_context_size", "reasoning", "include");
    }

    private static AiConversationStreamingRequest request(
            AiConversationReasoningEffort effort) {
        return new AiConversationStreamingRequest(
                new AiConversationModelRequest(
                        AiModelProvider.ANTHROPIC,
                        "claude-test",
                        256,
                        effort,
                        prompt()),
                AiConversationWebSearchMode.OFF);
    }

    private static AiConversationStreamingRequest searchRequest(
            AiConversationWebSearchMode mode) {
        return new AiConversationStreamingRequest(
                new AiConversationModelRequest(
                        AiModelProvider.ANTHROPIC,
                        "claude-test",
                        256,
                        AiConversationReasoningEffort.MEDIUM,
                        prompt()),
                mode);
    }

    private static AiConversationPromptSnapshot prompt() {
        return new AiConversationPromptSnapshot(
                "system", null, null, List.of(),
                new AiConversationContent("hello", List.of()),
                "generation", 5, false);
    }
}
