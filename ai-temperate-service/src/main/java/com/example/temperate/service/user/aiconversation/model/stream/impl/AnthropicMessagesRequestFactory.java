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
 * 构造不包含工具声明的 Anthropic Messages 普通对话请求。
 */
final class AnthropicMessagesRequestFactory {

    private final ObjectMapper objectMapper;

    AnthropicMessagesRequestFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    JsonNode create(AiConversationStreamingRequest request) {
        return create(request, AiConversationAttachment::url);
    }

    JsonNode create(
            AiConversationStreamingRequest request,
            Function<AiConversationAttachment, String> imageUrlResolver) {
        requireProvider(request);
        if (request.webSearchMode() != AiConversationWebSearchMode.OFF) {
            throw new IllegalArgumentException(
                    "Anthropic chat request requires OFF web search mode.");
        }
        return AnthropicRequestJsonSupport.createBase(
                objectMapper, request, imageUrlResolver);
    }

    private static void requireProvider(AiConversationStreamingRequest request) {
        Objects.requireNonNull(request);
        if (request.modelRequest().provider() != AiModelProvider.ANTHROPIC) {
            throw new IllegalArgumentException(
                    "Anthropic request requires the Anthropic provider.");
        }
        AiModelProvider.ANTHROPIC.validateReasoningEffort(
                request.modelRequest().reasoningEffort());
    }
}
