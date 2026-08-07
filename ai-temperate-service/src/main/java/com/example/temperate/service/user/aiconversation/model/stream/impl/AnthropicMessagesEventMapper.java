package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.model.AiConversationModelChunk;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationActivityPhase;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationActivityStatus;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationSourceRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将 Anthropic Messages SSE 增量映射为项目稳定事件，并只保留计费、活动和引用所需的最小字段。
 */
final class AnthropicMessagesEventMapper {

    private static final String SEARCH_ACTIVITY_ID = "anthropic-web-search";
    private static final String REASONING_ACTIVITY_ID = "anthropic-reasoning";

    private final ObjectMapper objectMapper;
    private long promptTokens;
    private long cachedPromptTokens;
    private long completionTokens;
    private String requestId;
    private boolean inputUsageAvailable;

    AnthropicMessagesEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    List<AiConversationModelEvent> map(OpenAiResponsesSseEvent event) {
        if (event.data() == null || event.data().isBlank()
                || "[DONE]".equals(event.data())) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(event.data());
            String type = text(root, "type", event.name());
            return switch (type) {
                case "message_start" -> messageStart(root);
                case "content_block_start" -> contentBlockStart(root);
                case "content_block_delta" -> contentBlockDelta(root);
                case "content_block_stop" -> contentBlockStop(root);
                case "message_delta" -> messageDelta(root);
                case "error" -> List.of(new AiConversationModelEvent.Failure(
                        text(root.path("error"), "type", "anthropic_error")));
                default -> List.of();
            };
        } catch (Exception exception) {
            return List.of(new AiConversationModelEvent.Failure(
                    "anthropic_sse_decode_failed"));
        }
    }

    private List<AiConversationModelEvent> messageStart(JsonNode root) {
        JsonNode message = root.path("message");
        requestId = safeIdentifier(text(message, "id", null));
        JsonNode usage = message.path("usage");
        JsonNode inputTokens = usage.path("input_tokens");
        JsonNode cachedTokens = usage.path("cache_read_input_tokens");
        promptTokens = tokenOrZero(inputTokens);
        cachedPromptTokens = cachedTokens.isMissingNode()
                ? 0L : tokenOrZero(cachedTokens);
        inputUsageAvailable = validToken(inputTokens)
                && (cachedTokens.isMissingNode() || validToken(cachedTokens))
                && cachedPromptTokens <= promptTokens;
        return List.of();
    }

    private List<AiConversationModelEvent> contentBlockStart(JsonNode root) {
        JsonNode block = root.path("content_block");
        String type = text(block, "type", "");
        if ("server_tool_use".equals(type)
                && "web_search".equals(text(block, "name", ""))) {
            return List.of(new AiConversationModelEvent.Activity(
                    SEARCH_ACTIVITY_ID,
                    AiConversationActivityPhase.WEB_SEARCH,
                    AiConversationActivityStatus.STARTED,
                    query(block.path("input"))));
        }
        if ("web_search_tool_result".equals(type)) {
            List<AiConversationModelEvent> events = new ArrayList<>();
            appendSources(block.path("content"), events);
            events.add(new AiConversationModelEvent.Activity(
                    SEARCH_ACTIVITY_ID,
                    AiConversationActivityPhase.WEB_SEARCH,
                    AiConversationActivityStatus.COMPLETED,
                    null));
            return List.copyOf(events);
        }
        return List.of();
    }

    private List<AiConversationModelEvent> contentBlockDelta(JsonNode root) {
        JsonNode delta = root.path("delta");
        String type = text(delta, "type", "");
        if ("text_delta".equals(type)) {
            String value = text(delta, "text", "");
            return value.isEmpty() ? List.of() : List.of(
                    new AiConversationModelEvent.Chunk(new AiConversationModelChunk(
                            value, null, requestId, null)));
        }
        if ("thinking_delta".equals(type)
                || "thinking_summary_delta".equals(type)) {
            String value = text(delta, "thinking",
                    text(delta, "text", ""));
            return value.isEmpty() ? List.of() : List.of(
                    new AiConversationModelEvent.ReasoningSummaryDelta(
                            REASONING_ACTIVITY_ID, value));
        }
        if ("citations_delta".equals(type)) {
            AiConversationModelEvent.Source source = source(
                    delta.path("citation"), AiConversationSourceRole.CITED);
            return source == null ? List.of() : List.of(source);
        }
        return List.of();
    }

    private List<AiConversationModelEvent> contentBlockStop(JsonNode root) {
        return List.of();
    }

    private List<AiConversationModelEvent> messageDelta(JsonNode root) {
        JsonNode outputTokens = root.path("usage").path("output_tokens");
        if (!inputUsageAvailable || !validToken(outputTokens)) {
            return List.of(new AiConversationModelEvent.Chunk(
                    new AiConversationModelChunk(
                            "",
                            null,
                            requestId,
                            text(root.path("delta"), "stop_reason", null))));
        }
        completionTokens = Math.max(completionTokens,
                tokenOrZero(outputTokens));
        AiConversationUsage usage = new AiConversationUsage(
                promptTokens,
                cachedPromptTokens,
                completionTokens,
                0L);
        return List.of(new AiConversationModelEvent.Chunk(
                new AiConversationModelChunk(
                        "",
                        usage,
                        requestId,
                        text(root.path("delta"), "stop_reason", null))));
    }

    private void appendSources(
            JsonNode node, List<AiConversationModelEvent> events) {
        if (node.isArray()) {
            node.forEach(item -> appendSources(item, events));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        AiConversationModelEvent.Source source = source(
                node, AiConversationSourceRole.CONSULTED);
        if (source != null) {
            events.add(source);
        }
    }

    private AiConversationModelEvent.Source source(
            JsonNode node, AiConversationSourceRole role) {
        String url = text(node, "url", null);
        if (url == null || (!url.startsWith("https://")
                && !url.startsWith("http://"))) {
            return null;
        }
        String title = text(node, "title", url);
        String sourceId = Integer.toUnsignedString(url.hashCode(), 36);
        String domain;
        try {
            domain = Objects.requireNonNullElse(URI.create(url).getHost(), "unknown");
        } catch (IllegalArgumentException exception) {
            domain = "unknown";
        }
        return new AiConversationModelEvent.Source(
                SEARCH_ACTIVITY_ID, sourceId, title, url, domain, role);
    }

    private static String query(JsonNode input) {
        return text(input, "query", text(input, "q", null));
    }

    private static boolean validToken(JsonNode node) {
        return node != null && node.isIntegralNumber()
                && node.canConvertToLong() && node.longValue() >= 0L;
    }

    private static long tokenOrZero(JsonNode node) {
        return validToken(node) ? node.longValue() : 0L;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : fallback;
    }

    private static String safeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}")
                ? value : null;
    }
}
