package com.example.temperate.service.user.aiconversation.generation.input;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 在旧附件数组和 v2 输入信封之间执行有界 JSON 转换，并确保数据库中只出现控制元数据而没有媒体字节。
 */
@Component
public final class AiConversationGenerationInputCodec {

    private static final int CURRENT_SCHEMA_VERSION = 2;
    private static final TypeReference<List<AiConversationAttachment>> ATTACHMENTS =
            new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public AiConversationGenerationInputCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public String encode(
            List<AiConversationAttachment> attachments,
            AiConversationImageGenerationOptions imageGeneration) {
        if (imageGeneration == null) {
            return json(attachments == null ? List.of() : List.copyOf(attachments));
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.set("attachments", objectMapper.valueToTree(
                attachments == null ? List.of() : List.copyOf(attachments)));
        ObjectNode generation = root.putObject("generation");
        generation.put("kind", "IMAGE");
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
                        null);
            }
            if (!root.isObject()
                    || root.path("schemaVersion").asInt(-1) != CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported Generation input schema version.");
            }
            List<AiConversationAttachment> attachments = objectMapper.convertValue(
                    root.path("attachments"), ATTACHMENTS);
            JsonNode generation = root.path("generation");
            if (generation.isMissingNode() || generation.isNull()) {
                return new AiConversationGenerationInputSnapshot(attachments, null);
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
                            generation.path("partialImages").asInt(-1));
            if (!options.size().equals(requiredText(generation, "size"))) {
                throw new IllegalArgumentException("Generation image size is inconsistent.");
            }
            return new AiConversationGenerationInputSnapshot(attachments, options);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "AI Generation input payload is invalid.", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "AI Generation input serialization failed.", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.asText();
    }

    private static String requireJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Generation input JSON is required.");
        }
        return value;
    }
}
