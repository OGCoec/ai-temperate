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
 * 按 Anthropic 工具白名单独立构造 Messages 联网搜索请求。
 */
final class AnthropicWebSearchRequestFactory {

    private final ObjectMapper objectMapper;

    AnthropicWebSearchRequestFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    JsonNode create(AiConversationStreamingRequest request) {
        return create(request, AiConversationAttachment::url);
    }

    JsonNode create(
            AiConversationStreamingRequest request,
            Function<AiConversationAttachment, String> imageUrlResolver) {
        Objects.requireNonNull(request);
        if (request.modelRequest().provider() != AiModelProvider.ANTHROPIC) {
            throw new IllegalArgumentException(
                    "Anthropic web search requires the Anthropic provider.");
        }
        if (request.webSearchMode() == AiConversationWebSearchMode.OFF) {
            throw new IllegalArgumentException(
                    "Anthropic web search request cannot use OFF mode.");
        }
        AiModelProvider.ANTHROPIC.validateReasoningEffort(
                request.modelRequest().reasoningEffort());
        ObjectNode root = AnthropicRequestJsonSupport.createBase(
                objectMapper, request, imageUrlResolver);
        root.putArray("tools").addObject()
                .put("type", "web_search_20260318")
                .put("name", "web_search")
                .put("max_uses", 5);
        ObjectNode toolChoice = root.putObject("tool_choice");
        if (request.webSearchMode() == AiConversationWebSearchMode.REQUIRED) {
            toolChoice.put("type", "tool").put("name", "web_search");
        } else {
            toolChoice.put("type", "auto");
        }
        return root;
    }
}
