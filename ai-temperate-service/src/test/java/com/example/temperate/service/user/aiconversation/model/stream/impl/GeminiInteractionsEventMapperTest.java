package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 验证 Google Interactions 会忽略 thought signature，并严格聚合单张图片和 TOKEN Usage。
 */
final class GeminiInteractionsEventMapperTest {

    @Test
    void mapsExactlyOneImageAndTokenUsageAtCompletion() {
        GeminiInteractionsEventMapper mapper = new GeminiInteractionsEventMapper(
                new ObjectMapper(), options(), (short) 0, 1024);
        mapper.map(event("interaction.created", """
                {"event_type":"interaction.created","interaction":{"id":"int-safe"}}
                """));
        assertThat(mapper.map(event("step.delta", """
                {"event_type":"step.delta","delta":{"type":"thought_signature","data":"secret"}}
                """))).isEmpty();
        mapper.map(event("step.delta", """
                {"event_type":"step.delta","delta":{"type":"image","data":"%s"}}
                """.formatted(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}))));

        var events = mapper.map(event("interaction.completed", """
                {"event_type":"interaction.completed","interaction":{"status":"completed",
                 "usage":{"total_input_tokens":11,"total_cached_tokens":2,
                 "total_output_tokens":5,"total_thought_tokens":1}}}
                """));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(AiConversationModelEvent.Image.class);
        AiConversationModelEvent.ImageUsage usage =
                (AiConversationModelEvent.ImageUsage) events.get(1);
        assertThat(usage.usage().basis().name()).isEqualTo("TOKEN");
    }

    @Test
    void rejectsZeroOrMultipleFinalImages() {
        GeminiInteractionsEventMapper empty = new GeminiInteractionsEventMapper(
                new ObjectMapper(), options(), (short) 0, 1024);
        assertThat(empty.map(completed())).singleElement()
                .isInstanceOf(AiConversationModelEvent.Failure.class);

        GeminiInteractionsEventMapper multiple = new GeminiInteractionsEventMapper(
                new ObjectMapper(), options(), (short) 0, 1024);
        String encoded = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        multiple.map(image(encoded));
        multiple.map(image(encoded));
        assertThat(multiple.map(completed())).singleElement()
                .isInstanceOf(AiConversationModelEvent.Failure.class);
    }

    private static AiConversationImageGenerationOptions options() {
        return new AiConversationImageGenerationOptions(
                AiConversationImageGenerationOptions.CURRENT_PROFILE_VERSION,
                AiConversationImageAspect.SQUARE,
                AiConversationImageQuality.LOW,
                512,
                512,
                AiConversationReasoningEffort.LOW,
                "webp",
                90,
                3,
                AiConversationImageAction.GENERATE,
                (short) 1);
    }

    private static OpenAiResponsesSseEvent event(String name, String data) {
        return new OpenAiResponsesSseEvent(name, data);
    }

    private static OpenAiResponsesSseEvent image(String encoded) {
        return event("step.delta", """
                {"event_type":"step.delta","delta":{"type":"image","data":"%s"}}
                """.formatted(encoded));
    }

    private static OpenAiResponsesSseEvent completed() {
        return event("interaction.completed", """
                {"event_type":"interaction.completed","interaction":{"status":"completed",
                 "usage":{"total_input_tokens":1,"total_cached_tokens":0,
                 "total_output_tokens":1,"total_thought_tokens":0}}}
                """);
    }
}
