package com.example.temperate.service.user.openaicompatibility.impl;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.openaicompatibility.OpenAiRequestPayloadMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 该基类是来复用请求体、布尔值、Token、工具预算和媒体能力的硬边界，协议实现只负责各自字段投影。
 */
abstract class LooseOpenAiRequestNormalizerSupport {

    protected static final Set<String> IMAGE_INPUT_TYPES = Set.of(
            "input_image", "image_url");
    protected static final Set<String> AUDIO_INPUT_TYPES = Set.of(
            "input_audio", "audio");
    protected static final Set<String> VIDEO_INPUT_TYPES = Set.of(
            "input_video", "video_url");
    protected static final Set<String> FILE_INPUT_TYPES = Set.of(
            "input_file", "file");

    protected final ApiKeyProperties properties;
    protected final ObjectMapper objectMapper;

    LooseOpenAiRequestNormalizerSupport(
            ApiKeyProperties properties,
            ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    protected OpenAiRequestPayloadMode payloadMode(AiModelCacheEntry model) {
        String canonical = model.modelName().trim().toLowerCase(Locale.ROOT);
        boolean passthrough = properties.getOpenAiCompatibility()
                .getPassthroughModels().stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(canonical::equals);
        return passthrough
                ? OpenAiRequestPayloadMode.CONTROLLED_PASSTHROUGH
                : OpenAiRequestPayloadMode.LOOSE_NORMALIZED;
    }

    protected void enforceBodySize(ObjectNode payload) {
        try {
            if (objectMapper.writeValueAsBytes(payload).length
                    > properties.getRequest().getMaxBodyBytes()) {
                throw ApiChatException.invalid("Request body exceeds 1 MiB.", null);
            }
        } catch (JsonProcessingException failure) {
            throw ApiChatException.invalid("Request body cannot be encoded.", null);
        }
    }

    protected boolean booleanValue(
            JsonNode value,
            String parameter,
            boolean fallback) {
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw ApiChatException.invalid(
                    parameter + " must be a JSON boolean.", parameter);
        }
        return value.booleanValue();
    }

    protected long effectiveTokenLimit(
            JsonNode preferred,
            String preferredParameter,
            JsonNode fallback,
            String fallbackParameter,
            long modelMaximum) {
        Long preferredValue = positiveLong(preferred, preferredParameter);
        Long fallbackValue = positiveLong(fallback, fallbackParameter);
        Long requested = preferredValue == null ? fallbackValue : preferredValue;
        return requested == null ? modelMaximum : Math.min(requested, modelMaximum);
    }

    protected Long positiveLong(JsonNode value, String parameter) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw ApiChatException.invalid(
                    parameter + " must be a positive integer.", parameter);
        }
        long parsed = value.longValue();
        if (parsed <= 0L) {
            throw ApiChatException.invalid(
                    parameter + " must be a positive integer.", parameter);
        }
        return parsed;
    }

    protected void enforceToolBudget(JsonNode... toolGroups) {
        long totalBytes = 0L;
        int totalTools = 0;
        try {
            for (JsonNode tools : toolGroups) {
                if (tools == null || tools.isNull()) {
                    continue;
                }
                if (tools.isArray()) {
                    totalTools = Math.addExact(totalTools, tools.size());
                }
                totalBytes = Math.addExact(
                        totalBytes, objectMapper.writeValueAsBytes(tools).length);
            }
        } catch (JsonProcessingException | ArithmeticException failure) {
            throw ApiChatException.invalid("Tools cannot be encoded.", "tools");
        }
        if (totalTools > properties.getRequest().getMaxTools()) {
            throw ApiChatException.invalid("tools has an invalid size.", "tools");
        }
        if (totalBytes > properties.getRequest().getMaxToolDefinitionsBytes()) {
            throw ApiChatException.invalid(
                    "Tool definitions exceed the allowed UTF-8 size.", "tools");
        }
    }

    protected ArrayNode filterTools(JsonNode tools, AiModelCacheEntry model) {
        if (!(tools instanceof ArrayNode array)) {
            return null;
        }
        ArrayNode filtered = objectMapper.createArrayNode();
        for (JsonNode tool : array) {
            if (!(tool instanceof ObjectNode object)) {
                continue;
            }
            JsonNode typeNode = object.get("type");
            if (typeNode == null || !typeNode.isTextual()) {
                continue;
            }
            String type = typeNode.textValue();
            if ("function".equals(type)) {
                filtered.add(object.deepCopy());
            } else if (Set.of("web_search", "web_search_preview").contains(type)
                    && model.capabilities().contains(AiModelCapabilityCode.WEB_SEARCH)) {
                filtered.add(object.deepCopy());
            }
            // 已识别的托管工具会在这里静默删除，避免把当前平台不能履行的持续资源语义伪装成成功。
        }
        return filtered;
    }

    protected void enforceChatMediaCapabilities(
            JsonNode messages,
            AiModelCacheEntry model,
            String parameter) {
        if (!(messages instanceof ArrayNode array)) {
            return;
        }
        for (int index = 0; index < array.size(); index++) {
            JsonNode message = array.get(index);
            if (message instanceof ObjectNode object) {
                inspectContentBlocks(
                        object.get("content"), model,
                        parameter + "[" + index + "].content");
            }
        }
    }

    protected void enforceResponsesMediaCapabilities(
            JsonNode input,
            AiModelCacheEntry model,
            String parameter) {
        if (input == null || input.isNull() || input.isTextual()) {
            return;
        }
        if (input instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                enforceResponseInputItem(
                        array.get(index), model, parameter + "[" + index + "]");
            }
            return;
        }
        enforceResponseInputItem(input, model, parameter);
    }

    private void enforceResponseInputItem(
            JsonNode item,
            AiModelCacheEntry model,
            String parameter) {
        if (!(item instanceof ObjectNode object)) {
            return;
        }
        enforceMediaType(object, model, parameter);
        inspectContentBlocks(object.get("content"), model, parameter + ".content");
    }

    private void inspectContentBlocks(
            JsonNode content,
            AiModelCacheEntry model,
            String parameter) {
        if (content == null || content.isNull() || content.isTextual()) {
            return;
        }
        if (content instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                inspectContentBlocks(
                        array.get(index), model, parameter + "[" + index + "]");
            }
            return;
        }
        if (!(content instanceof ObjectNode object)) {
            return;
        }
        enforceMediaType(object, model, parameter);
        // 只递归显式 content 容器，禁止把函数输出或任意工具参数中的 type=file 误判成媒体输入。
        inspectContentBlocks(object.get("content"), model, parameter + ".content");
    }

    private static void enforceMediaType(
            ObjectNode object,
            AiModelCacheEntry model,
            String parameter) {
        JsonNode typeNode = object.get("type");
        if (typeNode != null && typeNode.isTextual()) {
            String type = typeNode.textValue();
            if (FILE_INPUT_TYPES.contains(type)) {
                throw ApiChatException.invalid(
                        "File input is not supported.", parameter + ".type");
            }
            requireMediaCapability(
                    type, IMAGE_INPUT_TYPES, AiModelCapabilityCode.IMAGE_INPUT,
                    model, parameter);
            requireMediaCapability(
                    type, AUDIO_INPUT_TYPES, AiModelCapabilityCode.AUDIO_INPUT,
                    model, parameter);
            requireMediaCapability(
                    type, VIDEO_INPUT_TYPES, AiModelCapabilityCode.VIDEO_INPUT,
                    model, parameter);
        }
    }

    private static void requireMediaCapability(
            String type,
            Set<String> recognizedTypes,
            AiModelCapabilityCode required,
            AiModelCacheEntry model,
            String parameter) {
        if (recognizedTypes.contains(type) && !model.capabilities().contains(required)) {
            throw ApiChatException.invalid(
                    "The model does not declare " + required + " capability.",
                    parameter + ".type");
        }
    }

    protected static int countRemovedFields(ObjectNode payload, List<String> fields) {
        int removed = 0;
        for (String field : fields) {
            if (payload.remove(field) != null) {
                removed++;
            }
        }
        return removed;
    }
}
