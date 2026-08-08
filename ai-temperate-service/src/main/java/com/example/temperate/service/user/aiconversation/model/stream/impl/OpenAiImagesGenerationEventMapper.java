package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImage;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImageFormat;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImagePhase;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
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
            AiConversationImageGenerationOptions options,
            short outputIndex) {
        return mapDetailed(event, options, outputIndex).events();
    }

    OpenAiImagesGenerationMappingResult mapDetailed(
            OpenAiResponsesSseEvent event,
            AiConversationImageGenerationOptions options,
            short outputIndex) {
        Objects.requireNonNull(options);
        if (event == null || event.data() == null) {
            return result(
                    event,
                    null,
                    null,
                    "none",
                    0,
                    OpenAiImagesGenerationMappingOutcome.EMPTY,
                    List.of());
        }
        if ("[DONE]".equals(event.data().trim())) {
            return result(
                    event,
                    null,
                    null,
                    "none",
                    0,
                    OpenAiImagesGenerationMappingOutcome.DONE,
                    List.of());
        }
        JsonNode root = parse(event.data());
        String type = text(root, "type");
        if (type == null || type.isBlank()) {
            type = event.name();
        }
        Integer partialImageIndex = diagnosticPartialIndex(root);
        ImagePayloadMetadata payload = imagePayload(root);
        OpenAiImagesGenerationMappingOutcome outcome;
        List<AiConversationModelEvent> events;
        switch (type == null ? "" : type) {
            case "image_generation.partial_image",
                    "image_edit.partial_image" -> {
                outcome = OpenAiImagesGenerationMappingOutcome.PARTIAL;
                events = partial(root, options, outputIndex);
            }
            case "image_generation.completed",
                    "image_edit.completed" -> {
                outcome = OpenAiImagesGenerationMappingOutcome.FINAL;
                events = completed(root, options, outputIndex);
            }
            case "error" -> {
                outcome = OpenAiImagesGenerationMappingOutcome.FAILURE;
                events = List.of(new AiConversationModelEvent.Failure(
                        "UPSTREAM_IMAGE_RESPONSE_FAILED"));
            }
            default -> {
                outcome = OpenAiImagesGenerationMappingOutcome.IGNORED;
                events = List.of();
            }
        }
        return result(
                event,
                type,
                partialImageIndex,
                payload.field(),
                payload.characters(),
                outcome,
                events);
    }

    private static OpenAiImagesGenerationMappingResult result(
            OpenAiResponsesSseEvent event,
            String jsonType,
            Integer partialImageIndex,
            String imagePayloadField,
            int encodedImageCharacters,
            OpenAiImagesGenerationMappingOutcome outcome,
            List<AiConversationModelEvent> events) {
        String data = event == null ? null : event.data();
        return new OpenAiImagesGenerationMappingResult(
                event == null ? null : event.name(),
                jsonType,
                partialImageIndex,
                imagePayloadField,
                data == null ? 0 : data.length(),
                encodedImageCharacters,
                outcome,
                events);
    }

    private static Integer diagnosticPartialIndex(JsonNode root) {
        JsonNode value = root.path("partial_image_index");
        return value.isIntegralNumber() && value.canConvertToInt()
                ? value.intValue()
                : null;
    }

    private static ImagePayloadMetadata imagePayload(JsonNode root) {
        String b64Json = text(root, "b64_json");
        if (b64Json != null) {
            return new ImagePayloadMetadata("b64_json", b64Json.length());
        }
        String partialImage = text(root, "partial_image_b64");
        if (partialImage != null) {
            return new ImagePayloadMetadata(
                    "partial_image_b64", partialImage.length());
        }
        return new ImagePayloadMetadata("none", 0);
    }

    private List<AiConversationModelEvent> partial(
            JsonNode root,
            AiConversationImageGenerationOptions options,
            short outputIndex) {
        JsonNode indexValue = root.path("partial_image_index");
        if (!indexValue.isIntegralNumber() || !indexValue.canConvertToInt()) {
            throw new IllegalStateException("Invalid partial image index");
        }
        int index = indexValue.intValue();
        if (index < 0
                || index >= AiConversationImageGenerationOptions.MAXIMUM_PARTIAL_IMAGES) {
            throw new IllegalStateException("Invalid partial image index");
        }
        byte[] bytes = decode(text(root, "b64_json"));
        return List.of(new AiConversationModelEvent.Image(
                image(root, options, AiConversationGeneratedImagePhase.PARTIAL,
                        outputIndex, (short) index, bytes)));
    }

    private List<AiConversationModelEvent> completed(
            JsonNode root,
            AiConversationImageGenerationOptions options,
            short outputIndex) {
        List<AiConversationModelEvent> result = new ArrayList<>();
        result.add(new AiConversationModelEvent.Image(
                image(root, options,
                        AiConversationGeneratedImagePhase.FINAL,
                        outputIndex,
                        null,
                        decode(text(root, "b64_json")))));
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
            long prompt = requiredNumber(usage, "input_tokens");
            long cached = optionalNumber(
                    usage,
                    "cached_tokens",
                    usage.path("input_tokens_details"));
            long completion = requiredNumber(usage, "output_tokens");
            long reasoning = optionalNumber(
                    usage,
                    "reasoning_tokens",
                    usage.path("output_tokens_details"));
            result.add(new AiConversationModelEvent.ImageUsage(
                    outputIndex,
                    new AiConversationUsage(
                            prompt, cached, completion, reasoning),
                    text(root, "id"),
                    "STOP"));
        }
        return List.copyOf(result);
    }

    private AiConversationGeneratedImage image(
            JsonNode root,
            AiConversationImageGenerationOptions options,
            AiConversationGeneratedImagePhase phase,
            short outputIndex,
            Short partialImageIndex,
            byte[] bytes) {
        String imageId = text(root, "item_id");
        if (imageId == null || imageId.isBlank()) {
            imageId = text(root, "id");
        }
        if (imageId == null || imageId.isBlank()) {
            imageId = "image-" + outputIndex;
        }
        // 请求格式只是偏好；预览、OSS 和数据库必须以解码后字节的真实格式为准。
        AiConversationGeneratedImageFormat format =
                AiConversationGeneratedImageFormat.detect(bytes);
        return new AiConversationGeneratedImage(
                imageId,
                phase,
                outputIndex,
                partialImageIndex,
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

    private static long requiredNumber(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0L) {
            throw new IllegalStateException(
                    "Image usage field is not a non-negative integer: " + field);
        }
        return value.longValue();
    }

    private static long optionalNumber(
            JsonNode directParent,
            String field,
            JsonNode nestedParent) {
        JsonNode direct = directParent.path(field);
        if (!direct.isMissingNode() && !direct.isNull()) {
            return requiredNumber(directParent, field);
        }
        JsonNode nested = nestedParent.path(field);
        return nested.isMissingNode() || nested.isNull()
                ? 0L
                : requiredNumber(nestedParent, field);
    }

    /**
     * 仅携带图片字段名称和字符数，防止诊断结果间接长期持有原始 Base64 字符串。
     */
    private record ImagePayloadMetadata(String field, int characters) {
    }
}
