package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelRequest;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Responses 请求体保持流式、禁用上游存储，并准确区分自动与强制联网搜索。
 */
final class OpenAiResponsesRequestFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiResponsesRequestFactory factory =
            new OpenAiResponsesRequestFactory(objectMapper);

    @Test
    void buildsAutoAndRequiredToolChoiceWithoutChangingPromptContent() {
        JsonNode auto = factory.create(request(AiConversationWebSearchMode.AUTO));
        JsonNode required = factory.create(
                request(AiConversationWebSearchMode.REQUIRED));

        assertThat(auto.path("stream").asBoolean()).isTrue();
        assertThat(auto.path("store").asBoolean()).isFalse();
        assertThat(auto.path("tools").path(0).path("type").asText())
                .isEqualTo("web_search");
        assertThat(auto.path("tools").path(0).path("search_context_size").asText())
                .isEqualTo("medium");
        assertThat(auto.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(required.path("tool_choice").path("type").asText())
                .isEqualTo("web_search");
        assertThat(auto.path("include").path(0).asText())
                .isEqualTo("web_search_call.action.sources");
        assertThat(auto.path("reasoning").path("summary").asText())
                .isEqualTo("auto");
        assertThat(auto.toString()).contains("hello web");
    }

    private static AiConversationStreamingRequest request(
            AiConversationWebSearchMode mode) {
        AiConversationPromptSnapshot prompt = new AiConversationPromptSnapshot(
                "system",
                null,
                null,
                List.of(),
                new AiConversationContent("hello web", List.of()),
                "generation",
                10,
                false);
        return new AiConversationStreamingRequest(
                new AiConversationModelRequest(
                        "gpt-test",
                        128,
                        AiConversationReasoningEffort.HIGH,
                        prompt),
                mode);
    }
}
