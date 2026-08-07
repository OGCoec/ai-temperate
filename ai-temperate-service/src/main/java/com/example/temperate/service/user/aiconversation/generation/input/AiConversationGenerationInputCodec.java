package com.example.temperate.service.user.aiconversation.generation.input;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 在旧附件数组及 v2 至 v4 输入信封之间执行有界 JSON 转换，并冻结附件、图片参数和联网模式而不保存媒体字节。
 */
@Component
public final class AiConversationGenerationInputCodec {

    private static final int LEGACY_IMAGE_SCHEMA_VERSION = 2;
    private static final int MULTI_IMAGE_SCHEMA_VERSION = 3;
    private static final int CURRENT_SCHEMA_VERSION = 4;
    private static final TypeReference<List<AiConversationAttachment>> ATTACHMENTS =
            new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public AiConversationGenerationInputCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public String encode(
            List<AiConversationAttachment> attachments,
            AiConversationImageGenerationOptions imageGeneration) {
        return encode(
                attachments, imageGeneration, AiConversationWebSearchMode.OFF);
    }

    public String encode(
            List<AiConversationAttachment> attachments,
            AiConversationImageGenerationOptions imageGeneration,
            AiConversationWebSearchMode webSearchMode) {
        Objects.requireNonNull(webSearchMode);
        if (imageGeneration != null
                && webSearchMode != AiConversationWebSearchMode.OFF) {
            throw new IllegalArgumentException(
                    "Image generation cannot enable web search.");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.put("webSearchMode", webSearchMode.name());
        root.set("attachments", objectMapper.valueToTree(
                attachments == null ? List.of() : List.copyOf(attachments)));
        if (imageGeneration == null) {
            return root.toString();
        }
        ObjectNode generation = root.putObject("generation");
        generation.put("kind", "IMAGE");
        generation.put("action", imageGeneration.action().name());
        generation.put("outputCount", imageGeneration.outputCount());
        generation.put("profileVersion", imageGeneration.profileVersion());
        generation.put("aspect", imageGeneration.aspect().name());
        generation.put("quality", imageGeneration.quality().name());
        generation.put("width", imageGeneration.width());
        generation.put("height", imageGeneration.height());
        generation.put("size", imageGeneration.size());
        generation.put("reasoningEffort", imageGeneration.reasoningEffort().name());
        generation.put("format", imageGeneration.outputFormat());
        generation.put("compression", imageGeneration.outputCompression());
        generation.put("partialImages", imageGeneration.partialImages());
        return root.toString();
    }

    public AiConversationGenerationInputSnapshot decode(String json) {
        try {
            JsonNode root = objectMapper.readTree(requireJson(json));
            if (root.isArray()) {
                return new AiConversationGenerationInputSnapshot(
                        objectMapper.convertValue(root, ATTACHMENTS),
                        null,
                        AiConversationWebSearchMode.OFF);
            }
            int schemaVersion = root.path("schemaVersion").asInt(-1);
            if (!root.isObject()
                    || (schemaVersion != LEGACY_IMAGE_SCHEMA_VERSION
                    && schemaVersion != MULTI_IMAGE_SCHEMA_VERSION
                    && schemaVersion != CURRENT_SCHEMA_VERSION)) {
                throw new IllegalArgumentException(
                        "Unsupported Generation input schema version.");
            }
            List<AiConversationAttachment> attachments = objectMapper.convertValue(
                    root.path("attachments"), ATTACHMENTS);
            AiConversationWebSearchMode webSearchMode =
                    schemaVersion == CURRENT_SCHEMA_VERSION
                            ? AiConversationWebSearchMode.valueOf(
                                    requiredText(root, "webSearchMode"))
                            : AiConversationWebSearchMode.OFF;
            JsonNode generation = root.path("generation");
            if (generation.isMissingNode() || generation.isNull()) {
                return new AiConversationGenerationInputSnapshot(
                        attachments, null, webSearchMode);
            }
            if (webSearchMode != AiConversationWebSearchMode.OFF) {
                throw new IllegalArgumentException(
                        "Image generation cannot enable web search.");
            }
            if (!"IMAGE".equals(generation.path("kind").asText())) {
                throw new IllegalArgumentException("Unsupported Generation input kind.");
            }
            AiConversationImageGenerationOptions options =
                    new AiConversationImageGenerationOptions(
                            requiredText(generation, "profileVersion"),
                            AiConversationImageAspect.valueOf(
                                    requiredText(generation, "aspect")),
                            AiConversationImageQuality.valueOf(
                                    requiredText(generation, "quality")),
                            generation.path("width").asInt(),
                            generation.path("height").asInt(),
                            AiConversationReasoningEffort.valueOf(
                                    requiredText(generation, "reasoningEffort")),
                            requiredText(generation, "format"),
                            generation.path("compression").asInt(),
                            generation.path("partialImages").asInt(-1),
                            schemaVersion == LEGACY_IMAGE_SCHEMA_VERSION
                                    ? AiConversationImageAction.GENERATE
                                    : AiConversationImageAction.valueOf(
                                            requiredText(generation, "action")),
                            schemaVersion == LEGACY_IMAGE_SCHEMA_VERSION
                                    ? (short) 1
                                    : requiredOutputCount(generation));
            if (!options.size().equals(requiredText(generation, "size"))) {
                throw new IllegalArgumentException("Generation image size is inconsistent.");
            }
            return new AiConversationGenerationInputSnapshot(
                    attachments, options, webSearchMode);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "AI Generation input payload is invalid.", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.asText();
    }

    private static short requiredOutputCount(JsonNode node) {
        JsonNode value = node.path("outputCount");
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("outputCount is required.");
        }
        int outputCount = value.intValue();
        if (outputCount < 1 || outputCount > 10) {
            throw new IllegalArgumentException("outputCount is out of range.");
        }
        return (short) outputCount;
    }

    private static String requireJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Generation input JSON is required.");
        }
        return value;
    }
}
