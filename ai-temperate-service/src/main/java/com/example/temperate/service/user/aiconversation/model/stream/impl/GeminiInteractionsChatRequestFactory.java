package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.function.Function;

/**
 * 构造不声明工具的 Google Interactions 普通文本请求。
 */
final class GeminiInteractionsChatRequestFactory {

    private final ObjectMapper objectMapper;

    GeminiInteractionsChatRequestFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    JsonNode create(AiConversationStreamingRequest request) {
        return create(request, AiConversationAttachment::url);
    }

    JsonNode create(
            AiConversationStreamingRequest request,
            Function<AiConversationAttachment, String> imageUrlResolver) {
        requireGoogle(request);
        if (request.webSearchMode() != AiConversationWebSearchMode.OFF) {
            throw new IllegalArgumentException(
                    "Google chat request requires OFF web search mode.");
        }
        return GeminiInteractionsRequestJsonSupport.createTextBase(
                objectMapper, request, imageUrlResolver);
    }

    private static void requireGoogle(AiConversationStreamingRequest request) {
        Objects.requireNonNull(request);
        if (request.modelRequest().provider() != AiModelProvider.GOOGLE) {
            throw new IllegalArgumentException(
                    "Google request requires the Google provider.");
        }
        AiModelProvider.GOOGLE.validateReasoningEffort(
                request.modelRequest().reasoningEffort());
    }
}
