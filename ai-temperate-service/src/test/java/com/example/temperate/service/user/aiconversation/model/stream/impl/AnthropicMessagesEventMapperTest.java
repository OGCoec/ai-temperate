package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 验证 Anthropic SSE 文本、思考摘要和最终 Token Usage 会被转换为现有内部事件。
 */
final class AnthropicMessagesEventMapperTest {

    @Test
    void mapsTextThinkingAndFinalUsageWithoutRawProviderPayload() {
        AnthropicMessagesEventMapper mapper =
                new AnthropicMessagesEventMapper(new ObjectMapper());
        mapper.map(event("message_start", """
                {"type":"message_start","message":{"id":"msg-safe","usage":{
                  "input_tokens":12,"cache_read_input_tokens":3}}}
                """));

        assertThat(mapper.map(event("content_block_delta", """
                {"type":"content_block_delta","delta":{"type":"text_delta","text":"hello"}}
                """))).singleElement().isInstanceOf(AiConversationModelEvent.Chunk.class);
        assertThat(mapper.map(event("content_block_delta", """
                {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"plan"}}
                """))).singleElement().isInstanceOf(
                        AiConversationModelEvent.ReasoningSummaryDelta.class);

        AiConversationModelEvent.Chunk completed =
                (AiConversationModelEvent.Chunk) mapper.map(event(
                        "message_delta", """
                        {"type":"message_delta","delta":{"stop_reason":"end_turn"},
                         "usage":{"output_tokens":7}}
                        """)).getFirst();
        assertThat(completed.value().usage().promptTokens()).isEqualTo(12);
        assertThat(completed.value().usage().cachedPromptTokens()).isEqualTo(3);
        assertThat(completed.value().usage().completionTokens()).isEqualTo(7);
    }

    private static OpenAiResponsesSseEvent event(String name, String data) {
        return new OpenAiResponsesSseEvent(name, data);
    }
}
