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
 * 验证 xAI Responses 请求只发送其公开协议允许的联网搜索与推理字段。
 */
final class XaiResponsesRequestFactoryTest {

    private final XaiResponsesRequestFactory factory =
            new XaiResponsesRequestFactory(new ObjectMapper());

    @Test
    void usesStringToolChoiceAndOmitsOpenAiOnlyFields() {
        JsonNode required = factory.create(request(
                AiConversationWebSearchMode.REQUIRED));
        JsonNode auto = factory.create(request(AiConversationWebSearchMode.AUTO));

        assertThat(required.path("tools").path(0).path("type").asText())
                .isEqualTo("web_search");
        assertThat(required.path("tool_choice").asText()).isEqualTo("required");
        assertThat(auto.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(required.path("tools").path(0).has("search_context_size"))
                .isFalse();
        assertThat(required.path("reasoning").path("effort").asText())
                .isEqualTo("medium");
        assertThat(required.path("reasoning").has("summary")).isFalse();
        assertThat(required.path("include").path(0).asText())
                .isEqualTo("web_search_call.action.sources");
        assertThat(required.toString()).doesNotContain("search_context_size");
    }

    private static AiConversationStreamingRequest request(
            AiConversationWebSearchMode mode) {
        AiConversationPromptSnapshot prompt = new AiConversationPromptSnapshot(
                "system", null, null, List.of(),
                new AiConversationContent("search safely", List.of()),
                "generation", 10, false);
        return new AiConversationStreamingRequest(
                new AiConversationModelRequest(
                        AiModelProvider.XAI,
                        "grok-test",
                        128,
                        AiConversationReasoningEffort.MEDIUM,
                        prompt),
                mode);
    }
}
