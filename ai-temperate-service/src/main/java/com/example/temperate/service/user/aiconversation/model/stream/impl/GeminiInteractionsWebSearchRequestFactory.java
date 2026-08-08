package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.function.Function;

/**
 * 按 Google Interactions 工具白名单构造 AUTO 或 REQUIRED 联网搜索请求。
 */
final class GeminiInteractionsWebSearchRequestFactory {

    private final ObjectMapper objectMapper;

    GeminiInteractionsWebSearchRequestFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    JsonNode create(AiConversationStreamingRequest request) {
        return create(request, AiConversationAttachment::url);
    }

    JsonNode create(
            AiConversationStreamingRequest request,
            Function<AiConversationAttachment, String> imageUrlResolver) {
        Objects.requireNonNull(request);
        if (request.modelRequest().provider() != AiModelProvider.GOOGLE) {
            throw new IllegalArgumentException(
                    "Google web search requires the Google provider.");
        }
        if (request.webSearchMode() == AiConversationWebSearchMode.OFF) {
            throw new IllegalArgumentException(
                    "Google web search request cannot use OFF mode.");
        }
        AiModelProvider.GOOGLE.validateReasoningEffort(
                request.modelRequest().reasoningEffort());
        ObjectNode root = GeminiInteractionsRequestJsonSupport.createTextBase(
                objectMapper, request, imageUrlResolver);
        root.putArray("tools").addObject()
                .put("type", "google_search")
                .putArray("search_types")
                .add("web_search");
        root.put("tool_choice",
                request.webSearchMode() == AiConversationWebSearchMode.REQUIRED
                        ? "any" : "auto");
        return root;
    }
}
