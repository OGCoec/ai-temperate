package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.model.AiConversationModelChunk;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationActivityPhase;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationActivityStatus;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationSourceRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 把 OpenAI Responses 原生事件转换为项目内部白名单事件，并在边界处过滤不可信来源 URL 与超长文本。
 *
 * <p>未知供应商事件只累计固定维度指标，不记录事件正文或动态类型，避免内容泄露和高基数日志。</p>
 */
final class OpenAiResponsesEventMapper {

    private static final int MAXIMUM_QUERY_CHARACTERS = 1_024;
    private static final int MAXIMUM_TITLE_CHARACTERS = 512;
    private static final int MAXIMUM_URL_CHARACTERS = 4_096;
    private static final int MAXIMUM_SUMMARY_DELTA_CHARACTERS = 16_384;
    private static final Counter UNKNOWN_EVENT_COUNTER = Metrics.counter(
            "ai.conversation.responses.event",
            "outcome",
            "ignored_unknown");

    private final ObjectMapper objectMapper;

    OpenAiResponsesEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<AiConversationModelEvent> map(OpenAiResponsesSseEvent event) {
        if (event == null || event.data() == null
                || "[DONE]".equals(event.data().trim())) {
            return List.of();
        }
        JsonNode root = parse(event.data());
        String type = text(root, "type");
        if (type == null || type.isBlank()) {
            type = event.name();
        }
        return switch (type) {
            case "response.created", "response.in_progress" -> List.of(
                    new AiConversationModelEvent.Activity(
                            activityId(root, "processing"),
                            AiConversationActivityPhase.PROCESSING,
                            AiConversationActivityStatus.IN_PROGRESS,
                            null));
            case "response.web_search_call.in_progress",
                    "response.web_search_call.searching" -> List.of(
                            searchActivity(root, AiConversationActivityStatus.IN_PROGRESS));
            case "response.web_search_call.completed" -> List.of(
                            searchActivity(root, AiConversationActivityStatus.COMPLETED));
            case "response.web_search_call.failed" -> List.of(
                            searchActivity(root, AiConversationActivityStatus.FAILED));
            case "response.reasoning_summary_part.added",
                    "response.reasoning_summary_part.done",
                    "response.reasoning_summary_text.done" -> List.of(
                            new AiConversationModelEvent.Activity(
                                    activityId(root, "reasoning"),
                                    AiConversationActivityPhase.REASONING,
                                    type.endsWith("done")
                                            ? AiConversationActivityStatus.COMPLETED
                                            : AiConversationActivityStatus.IN_PROGRESS,
                                    null));
            case "response.reasoning_summary_text.delta" ->
                    reasoningSummary(root);
            case "response.output_text.delta" -> textDelta(root);
            case "response.output_text.annotation.added" ->
                    annotationAdded(root);
            case "response.output_item.added" -> outputItemAdded(root);
            case "response.output_item.done" -> outputItemDone(root);
            case "response.completed" -> completed(root);
            case "response.failed", "error" -> List.of(
                    new AiConversationModelEvent.Failure(
                            "UPSTREAM_RESPONSE_FAILED"));
            case "response.incomplete" -> List.of(
                    new AiConversationModelEvent.Failure(
                            "UPSTREAM_RESPONSE_INCOMPLETE"));
            default -> {
                UNKNOWN_EVENT_COUNTER.increment();
                yield List.of();
            }
        };
    }

    private JsonNode parse(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Malformed AI upstream Responses event", exception);
        }
    }

    private static AiConversationModelEvent.Activity searchActivity(
            JsonNode root,
            AiConversationActivityStatus status) {
        return new AiConversationModelEvent.Activity(
                activityId(root, "search"),
                AiConversationActivityPhase.WEB_SEARCH,
                status,
                limited(firstText(
                        root.path("item").path("action").path("query"),
                        root.path("action").path("query"),
                        root.path("query")), MAXIMUM_QUERY_CHARACTERS));
    }

    private static List<AiConversationModelEvent> reasoningSummary(
            JsonNode root) {
        String delta = limited(text(root, "delta"),
                MAXIMUM_SUMMARY_DELTA_CHARACTERS);
        if (delta == null || delta.isEmpty()) {
            return List.of();
        }
        return List.of(new AiConversationModelEvent.ReasoningSummaryDelta(
                activityId(root, "reasoning"), delta));
    }

    private static List<AiConversationModelEvent> textDelta(JsonNode root) {
        String delta = text(root, "delta");
        if (delta == null || delta.isEmpty()) {
            return List.of();
        }
        return List.of(new AiConversationModelEvent.Chunk(
                new AiConversationModelChunk(delta, null, null, null)));
    }

    private static List<AiConversationModelEvent> outputItemDone(JsonNode root) {
        JsonNode item = root.path("item");
        String activityId = firstNonBlank(
                text(item, "id"), activityId(root, "search"));
        List<AiConversationModelEvent> result = new ArrayList<>();
        addSources(result, item.path("action").path("sources"),
                activityId, AiConversationSourceRole.CONSULTED);
        addAnnotations(result, item, activityId);
        if ("web_search_call".equals(text(item, "type"))) {
            result.add(new AiConversationModelEvent.Activity(
                    activityId,
                    AiConversationActivityPhase.WEB_SEARCH,
                    AiConversationActivityStatus.COMPLETED,
                    limited(text(item.path("action"), "query"),
                            MAXIMUM_QUERY_CHARACTERS)));
        }
        return List.copyOf(result);
    }

    private static List<AiConversationModelEvent> outputItemAdded(JsonNode root) {
        JsonNode item = root.path("item");
        if (!"web_search_call".equals(text(item, "type"))) {
            return List.of();
        }
        return List.of(new AiConversationModelEvent.Activity(
                firstNonBlank(text(item, "id"), activityId(root, "search")),
                AiConversationActivityPhase.WEB_SEARCH,
                AiConversationActivityStatus.STARTED,
                limited(text(item.path("action"), "query"),
                        MAXIMUM_QUERY_CHARACTERS)));
    }

    private static List<AiConversationModelEvent> annotationAdded(
            JsonNode root) {
        List<AiConversationModelEvent> result = new ArrayList<>();
        JsonNode annotation = root.path("annotation");
        if (annotation.isMissingNode()) {
            return List.of();
        }
        addSources(result,
                singleElementArray(annotation),
                activityId(root, "citation"),
                AiConversationSourceRole.CITED);
        return List.copyOf(result);
    }

    private static List<AiConversationModelEvent> completed(JsonNode root) {
        JsonNode response = root.path("response");
        List<AiConversationModelEvent> result = new ArrayList<>();
        for (JsonNode output : response.path("output")) {
            String activityId = firstNonBlank(
                    text(output, "id"), "search-completed");
            addSources(result, output.path("action").path("sources"),
                    activityId, AiConversationSourceRole.CONSULTED);
            addAnnotations(result, output, activityId);
        }
        JsonNode usage = response.path("usage");
        long prompt = number(usage, "input_tokens");
        long completion = number(usage, "output_tokens");
        long cached = Math.min(prompt,
                number(usage.path("input_tokens_details"), "cached_tokens"));
        long reasoning = number(
                usage.path("output_tokens_details"), "reasoning_tokens");
        AiConversationUsage mappedUsage = usage.isMissingNode()
                || usage.isNull()
                ? null
                : new AiConversationUsage(
                        prompt, cached, completion, reasoning);
        result.add(new AiConversationModelEvent.Chunk(
                new AiConversationModelChunk(
                        "",
                        mappedUsage,
                        text(response, "id"),
                        "STOP")));
        return List.copyOf(result);
    }

    private static void addAnnotations(
            List<AiConversationModelEvent> result,
            JsonNode node,
            String activityId) {
        for (JsonNode content : node.path("content")) {
            addSources(result, content.path("annotations"), activityId,
                    AiConversationSourceRole.CITED);
        }
        addSources(result, node.path("annotations"), activityId,
                AiConversationSourceRole.CITED);
    }

    private static void addSources(
            List<AiConversationModelEvent> result,
            JsonNode sources,
            String activityId,
            AiConversationSourceRole role) {
        if (!sources.isArray()) {
            return;
        }
        for (JsonNode source : sources) {
            String url = firstText(
                    source.path("url"),
                    source.path("url_citation").path("url"));
            URI uri = safeHttpUri(url);
            if (uri == null) {
                continue;
            }
            String title = limited(firstText(
                    source.path("title"),
                    source.path("url_citation").path("title")),
                    MAXIMUM_TITLE_CHARACTERS);
            if (title == null || title.isBlank()) {
                title = uri.getHost();
            }
            String normalizedUrl = uri.toString();
            String sourceId = firstNonBlank(
                    text(source, "id"), stableSourceId(normalizedUrl));
            result.add(new AiConversationModelEvent.Source(
                    activityId,
                    sourceId,
                    title,
                    normalizedUrl,
                    uri.getHost().toLowerCase(Locale.ROOT),
                    role));
        }
    }

    private static JsonNode singleElementArray(JsonNode value) {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .arrayNode()
                .add(value);
    }

    private static URI safeHttpUri(String value) {
        String limited = limited(value, MAXIMUM_URL_CHARACTERS);
        if (limited == null || limited.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(limited.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String stableSourceId(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String activityId(JsonNode root, String fallback) {
        return firstNonBlank(
                text(root, "item_id"),
                text(root.path("item"), "id"),
                text(root.path("response"), "id"),
                fallback);
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "unknown";
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? Math.max(0L, value.longValue()) : 0L;
    }

    private static String limited(String value, int maximumCharacters) {
        if (value == null) {
            return null;
        }
        return value.length() <= maximumCharacters
                ? value
                : value.substring(0, maximumCharacters);
    }
}
