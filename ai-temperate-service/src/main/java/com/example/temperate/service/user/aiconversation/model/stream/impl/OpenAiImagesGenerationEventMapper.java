package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImage;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImageFormat;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImagePhase;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelChunk;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 把 Images Generation SSE 映射为完整中间图、唯一最终图和权威 usage，并拒绝超限 Base64。
 */
final class OpenAiImagesGenerationEventMapper {

    private final ObjectMapper objectMapper;
    private final int maximumDecodedBytes;

    OpenAiImagesGenerationEventMapper(
            ObjectMapper objectMapper,
            int maximumDecodedBytes) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        if (maximumDecodedBytes <= 0) {
            throw new IllegalArgumentException("maximumDecodedBytes must be positive");
        }
        this.maximumDecodedBytes = maximumDecodedBytes;
    }

    List<AiConversationModelEvent> map(
            OpenAiResponsesSseEvent event,
            AiConversationImageGenerationOptions options) {
        Objects.requireNonNull(options);
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
            case "image_generation.partial_image" ->
                    partial(root, options);
            case "image_generation.completed" -> completed(root, options);
            case "error" -> List.of(
                    new AiConversationModelEvent.Failure(
                            "UPSTREAM_IMAGE_RESPONSE_FAILED"));
            default -> List.of();
        };
    }

    private List<AiConversationModelEvent> partial(
            JsonNode root,
            AiConversationImageGenerationOptions options) {
        int index = root.path("partial_image_index").asInt(-1);
        if (index < 0
                || index >= AiConversationImageGenerationOptions.MAXIMUM_PARTIAL_IMAGES) {
            throw new IllegalStateException("Invalid partial image index");
        }
        byte[] bytes = decode(text(root, "b64_json"));
        return List.of(new AiConversationModelEvent.Image(
                image(root, options, AiConversationGeneratedImagePhase.PARTIAL,
                        index, bytes)));
    }

    private List<AiConversationModelEvent> completed(
            JsonNode root,
            AiConversationImageGenerationOptions options) {
        List<AiConversationModelEvent> result = new ArrayList<>();
        result.add(new AiConversationModelEvent.Image(
                image(root, options,
                        AiConversationGeneratedImagePhase.FINAL,
                        AiConversationImageGenerationOptions.MAXIMUM_PARTIAL_IMAGES,
                        decode(text(root, "b64_json")))));
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
            long prompt = number(usage, "input_tokens");
            long completion = number(usage, "output_tokens");
            result.add(new AiConversationModelEvent.Chunk(
                    new AiConversationModelChunk(
                            "",
                            new AiConversationUsage(
                                    prompt, 0L, completion, 0L),
                            text(root, "id"),
                            "STOP")));
        }
        return List.copyOf(result);
    }

    private AiConversationGeneratedImage image(
            JsonNode root,
            AiConversationImageGenerationOptions options,
            AiConversationGeneratedImagePhase phase,
            int index,
            byte[] bytes) {
        String imageId = text(root, "item_id");
        if (imageId == null || imageId.isBlank()) {
            imageId = text(root, "id");
        }
        if (imageId == null || imageId.isBlank()) {
            imageId = "image-" + index;
        }
        // 请求格式只是偏好；预览、OSS 和数据库必须以解码后字节的真实格式为准。
        AiConversationGeneratedImageFormat format =
                AiConversationGeneratedImageFormat.detect(bytes);
        return new AiConversationGeneratedImage(
                imageId,
                phase,
                index,
                format.contentType(),
                options.aspect().width(),
                options.aspect().height(),
                bytes);
    }

    private byte[] decode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Image event does not contain Base64 data");
        }
        long estimated = (long) value.length() * 3L / 4L;
        if (estimated > maximumDecodedBytes + 2L) {
            throw new IllegalStateException("Image event exceeds the decoded byte limit");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length == 0 || bytes.length > maximumDecodedBytes) {
                throw new IllegalStateException(
                        "Image event exceeds the decoded byte limit");
            }
            return bytes;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Image event contains invalid Base64", exception);
        }
    }

    private JsonNode parse(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Malformed AI upstream image event", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? Math.max(0L, value.longValue()) : 0L;
    }
}
