package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImage;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImagePhase;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
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
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 将 Google Interactions SSE 映射为内部文本或图片事件，并显式丢弃不可持久化的 thought signature。
 */
final class GeminiInteractionsEventMapper {

    private static final String SEARCH_ACTIVITY_ID = "google-web-search";
    private static final String REASONING_ACTIVITY_ID = "google-reasoning";

    private final ObjectMapper objectMapper;
    private final AiConversationImageGenerationOptions imageOptions;
    private final short outputIndex;
    private final int maximumDecodedImageBytes;
    private byte[] finalImage;
    private int imageCount;
    private String requestId;

    GeminiInteractionsEventMapper(
            ObjectMapper objectMapper,
            AiConversationImageGenerationOptions imageOptions,
            short outputIndex,
            int maximumDecodedImageBytes) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.imageOptions = imageOptions;
        this.outputIndex = outputIndex;
        this.maximumDecodedImageBytes = maximumDecodedImageBytes;
    }

    List<AiConversationModelEvent> map(OpenAiResponsesSseEvent event) {
        if (event.data() == null || event.data().isBlank()
                || "[DONE]".equals(event.data())) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(event.data());
            String type = text(root, "event_type", text(root, "type", event.name()));
            if (type.contains("thought_signature")) {
                return List.of();
            }
            return switch (type) {
                case "interaction.created" -> created(root);
                case "step.start" -> stepStart(root);
                case "step.delta" -> stepDelta(root);
                case "step.stop" -> stepStop(root);
                case "interaction.completed" -> completed(root);
                case "error", "interaction.failed" -> List.of(
                        new AiConversationModelEvent.Failure("google_interaction_failed"));
                default -> List.of();
            };
        } catch (Exception exception) {
            return List.of(new AiConversationModelEvent.Failure(
                    "google_sse_decode_failed"));
        }
    }

    private List<AiConversationModelEvent> created(JsonNode root) {
        requestId = safeIdentifier(text(root.path("interaction"), "id",
                text(root, "interaction_id", null)));
        return List.of();
    }

    private List<AiConversationModelEvent> stepStart(JsonNode root) {
        JsonNode step = root.path("step");
        if ("google_search_call".equals(text(step, "type", ""))) {
            return List.of(new AiConversationModelEvent.Activity(
                    SEARCH_ACTIVITY_ID,
                    AiConversationActivityPhase.WEB_SEARCH,
                    AiConversationActivityStatus.STARTED,
                    text(step, "query", null)));
        }
        return List.of();
    }

    private List<AiConversationModelEvent> stepDelta(JsonNode root) {
        JsonNode delta = root.path("delta");
        String type = text(delta, "type", "");
        if ("text".equals(type) || "text_delta".equals(type)) {
            String value = text(delta, "text", "");
            List<AiConversationModelEvent> events = new ArrayList<>();
            if (!value.isEmpty()) {
                events.add(new AiConversationModelEvent.Chunk(
                        new AiConversationModelChunk(
                                value, null, requestId, null)));
            }
            appendCitations(delta.path("annotations"), events);
            return List.copyOf(events);
        }
        if ("thought_summary".equals(type)
                || "thought_summary_delta".equals(type)) {
            String value = text(delta, "text", text(delta, "summary", ""));
            return value.isEmpty() ? List.of() : List.of(
                    new AiConversationModelEvent.ReasoningSummaryDelta(
                            REASONING_ACTIVITY_ID, value));
        }
        if ("image".equals(type) || "image_delta".equals(type)) {
            captureImage(text(delta, "data", text(delta, "base64",
                    text(delta.path("image"), "data", null))));
        }
        return List.of();
    }

    private List<AiConversationModelEvent> stepStop(JsonNode root) {
        JsonNode step = root.path("step");
        if ("google_search_call".equals(text(step, "type", ""))
                || "google_search_result".equals(text(step, "type", ""))) {
            List<AiConversationModelEvent> events = new ArrayList<>();
            appendSources(step.path("results"), events);
            events.add(new AiConversationModelEvent.Activity(
                    SEARCH_ACTIVITY_ID,
                    AiConversationActivityPhase.WEB_SEARCH,
                    AiConversationActivityStatus.COMPLETED,
                    null));
            return List.copyOf(events);
        }
        return List.of();
    }

    private List<AiConversationModelEvent> completed(JsonNode root) {
        JsonNode interaction = root.path("interaction");
        if (interaction.isMissingNode() || interaction.isNull()) {
            interaction = root;
        }
        JsonNode usageNode = interaction.path("usage");
        JsonNode inputTokens = usageNode.path("total_input_tokens");
        JsonNode cachedTokens = usageNode.path("total_cached_tokens");
        JsonNode outputTokens = usageNode.path("total_output_tokens");
        JsonNode thoughtTokens = usageNode.path("total_thought_tokens");
        long prompt = tokenOrZero(inputTokens);
        long cached = cachedTokens.isMissingNode()
                ? 0L : tokenOrZero(cachedTokens);
        boolean usageAvailable = validToken(inputTokens)
                && validToken(outputTokens)
                && (cachedTokens.isMissingNode() || validToken(cachedTokens))
                && (thoughtTokens.isMissingNode() || validToken(thoughtTokens))
                && cached <= prompt;
        AiConversationUsage usage = new AiConversationUsage(
                prompt,
                cached,
                tokenOrZero(outputTokens),
                tokenOrZero(thoughtTokens));
        if (imageOptions == null) {
            return List.of(new AiConversationModelEvent.Chunk(
                    new AiConversationModelChunk(
                            "", usageAvailable ? usage : null, requestId,
                            text(interaction, "status", "completed"))));
        }
        if (imageCount != 1 || finalImage == null) {
            return List.of(new AiConversationModelEvent.Failure(
                    "google_image_result_count_invalid"));
        }
        AiConversationGeneratedImage image = new AiConversationGeneratedImage(
                "google-image-"
                        + Objects.requireNonNullElse(requestId, "unavailable")
                        + "-" + outputIndex,
                AiConversationGeneratedImagePhase.FINAL,
                outputIndex,
                null,
                "image/jpeg",
                imageOptions.width(),
                imageOptions.height(),
                finalImage);
        List<AiConversationModelEvent> events = new ArrayList<>(2);
        events.add(new AiConversationModelEvent.Image(image));
        if (!usageAvailable) {
            events.add(new AiConversationModelEvent.Failure(
                    "google_image_usage_missing"));
            return List.copyOf(events);
        }
        events.add(new AiConversationModelEvent.ImageUsage(
                outputIndex, usage, requestId, "completed"));
        return List.copyOf(events);
    }

    private void captureImage(String encoded) {
        imageCount++;
        if (imageCount > 1 || encoded == null || encoded.isBlank()) {
            return;
        }
        long estimated = (long) encoded.length() * 3L / 4L;
        if (estimated > maximumDecodedImageBytes + 2L) {
            return;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length > 0 && decoded.length <= maximumDecodedImageBytes) {
                finalImage = decoded;
            }
        } catch (IllegalArgumentException ignored) {
            finalImage = null;
        }
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
        String url = text(node, "url", text(node, "uri", null));
        if (url == null || (!url.startsWith("https://")
                && !url.startsWith("http://"))) {
            return;
        }
        String domain;
        try {
            domain = Objects.requireNonNullElse(URI.create(url).getHost(), "unknown");
        } catch (IllegalArgumentException exception) {
            domain = "unknown";
        }
        events.add(new AiConversationModelEvent.Source(
                SEARCH_ACTIVITY_ID,
                Integer.toUnsignedString(url.hashCode(), 36),
                text(node, "title", url),
                url,
                domain,
                AiConversationSourceRole.CONSULTED));
    }

    private void appendCitations(
            JsonNode node, List<AiConversationModelEvent> events) {
        int before = events.size();
        appendSources(node, events);
        for (int index = before; index < events.size(); index++) {
            AiConversationModelEvent.Source source =
                    (AiConversationModelEvent.Source) events.get(index);
            events.set(index, new AiConversationModelEvent.Source(
                    source.activityId(),
                    source.sourceId(),
                    source.title(),
                    source.url(),
                    source.domain(),
                    AiConversationSourceRole.CITED));
        }
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
