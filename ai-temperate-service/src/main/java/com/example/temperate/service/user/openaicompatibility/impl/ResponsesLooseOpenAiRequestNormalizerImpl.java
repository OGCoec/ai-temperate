package com.example.temperate.service.user.openaicompatibility.impl;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.openaicompatibility.LooseOpenAiRequestContext;
import com.example.temperate.service.user.openaicompatibility.LooseOpenAiRequestNormalization;
import com.example.temperate.service.user.openaicompatibility.LooseOpenAiRequestNormalizer;
import com.example.temperate.service.user.openaicompatibility.OpenAiCompatibilityProtocol;
import com.example.temperate.service.user.openaicompatibility.OpenAiRequestPayloadMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 该实现是来把 Responses 方言投影为无状态宽松负载，保留复杂 input 和客户端元数据并过滤平台不能履行的托管工具。
 */
@Service
public final class ResponsesLooseOpenAiRequestNormalizerImpl
        extends LooseOpenAiRequestNormalizerSupport
        implements LooseOpenAiRequestNormalizer {

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "model", "input", "instructions", "stream", "max_output_tokens",
            "reasoning", "tools", "tool_choice", "parallel_tool_calls",
            "max_tool_calls", "text", "temperature", "top_p", "top_logprobs",
            "service_tier", "truncation", "safety_identifier", "user", "include",
            "prompt_cache_key", "prompt_cache_retention", "client_metadata", "store",
            "background", "previous_response_id", "conversation", "metadata");
    private static final List<String> STATE_FIELDS = List.of(
            "background", "previous_response_id", "conversation");

    public ResponsesLooseOpenAiRequestNormalizerImpl(
            ApiKeyProperties properties,
            ObjectMapper objectMapper) {
        super(properties, objectMapper);
    }

    @Override
    public OpenAiCompatibilityProtocol protocol() {
        return OpenAiCompatibilityProtocol.RESPONSES;
    }

    @Override
    public LooseOpenAiRequestNormalization normalize(LooseOpenAiRequestContext context) {
        if (context.protocol() != protocol()) {
            throw new IllegalArgumentException("Responses normalizer received another protocol");
        }
        ObjectNode raw = context.rawPayload();
        AiModelCacheEntry model = context.model();
        enforceBodySize(raw);
        OpenAiRequestPayloadMode mode = payloadMode(model);
        Projection projection = mode == OpenAiRequestPayloadMode.CONTROLLED_PASSTHROUGH
                ? new Projection(raw.deepCopy(), 0)
                : projectKnownFields(raw);
        ObjectNode payload = projection.payload();
        int dropped = projection.droppedFields();

        JsonNode input = payload.get("input");
        if (input == null || input.isNull()) {
            throw ApiChatException.invalid("input is required.", "input");
        }
        if (input.isArray() && (input.isEmpty()
                || input.size() > properties.getRequest().getMaxMessages())) {
            throw ApiChatException.invalid(
                    "input must contain 1 to 256 items.", "input");
        }
        enforceResponsesMediaCapabilities(input, model, "input");

        boolean stream = booleanValue(payload.get("stream"), "stream", false);
        long effectiveMax = effectiveTokenLimit(
                payload.get("max_output_tokens"), "max_output_tokens",
                null, "max_output_tokens", model.maxOutputTokens());
        positiveLong(payload.get("max_tool_calls"), "max_tool_calls");

        // Responses 在本项目始终无状态；即使模型进入透传白名单，这些字段也必须由网关事实覆盖。
        payload.put("model", model.modelName());
        payload.put("stream", stream);
        payload.put("store", false);
        payload.put("max_output_tokens", effectiveMax);
        dropped += countRemovedFields(payload, STATE_FIELDS);

        JsonNode originalTools = payload.get("tools");
        ArrayNode filteredTools = filterTools(originalTools, model);
        if (filteredTools != null) {
            dropped += Math.max(0, originalTools.size() - filteredTools.size());
            if (filteredTools.isEmpty()) {
                payload.remove("tools");
            } else {
                payload.set("tools", filteredTools);
            }
        }
        enforceToolBudget(payload.get("tools"));

        return new LooseOpenAiRequestNormalization(
                payload, stream, effectiveMax, false, mode, dropped);
    }

    private Projection projectKnownFields(ObjectNode raw) {
        ObjectNode projected = objectMapper.createObjectNode();
        int dropped = 0;
        var fields = raw.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (TOP_LEVEL_FIELDS.contains(field.getKey())) {
                projected.set(field.getKey(), field.getValue().deepCopy());
            } else {
                dropped++;
            }
        }
        return new Projection(projected, dropped);
    }

    private record Projection(ObjectNode payload, int droppedFields) {
    }
}
