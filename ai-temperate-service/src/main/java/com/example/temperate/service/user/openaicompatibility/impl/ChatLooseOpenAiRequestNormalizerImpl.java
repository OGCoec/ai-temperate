package com.example.temperate.service.user.openaicompatibility.impl;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
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
 * 该实现是来把 Chat Completions 方言投影为宽松公共负载，并仅对授权、Token、状态和媒体能力执行硬覆盖。
 */
@Service
public final class ChatLooseOpenAiRequestNormalizerImpl
        extends LooseOpenAiRequestNormalizerSupport
        implements LooseOpenAiRequestNormalizer {

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "model", "messages", "stream", "stream_options",
            "max_completion_tokens", "max_tokens", "temperature", "top_p",
            "presence_penalty", "frequency_penalty", "stop", "seed", "n",
            "reasoning_effort", "service_tier", "verbosity", "safety_identifier",
            "user", "logprobs", "top_logprobs", "prediction", "prompt_cache_key",
            "prompt_cache_options", "tools", "tool_choice", "parallel_tool_calls",
            "functions", "function_call", "response_format", "store", "metadata",
            "web_search_options");
    private static final Set<String> MESSAGE_FIELDS = Set.of(
            "role", "content", "name", "tool_calls", "tool_call_id",
            "function_call", "reasoning_content", "refusal", "audio");
    private static final List<String> MEDIA_OUTPUT_FIELDS = List.of("modalities", "audio");

    public ChatLooseOpenAiRequestNormalizerImpl(
            ApiKeyProperties properties,
            ObjectMapper objectMapper) {
        super(properties, objectMapper);
    }

    @Override
    public OpenAiCompatibilityProtocol protocol() {
        return OpenAiCompatibilityProtocol.CHAT_COMPLETIONS;
    }

    @Override
    public LooseOpenAiRequestNormalization normalize(LooseOpenAiRequestContext context) {
        if (context.protocol() != protocol()) {
            throw new IllegalArgumentException("Chat normalizer received another protocol");
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

        JsonNode messages = payload.get("messages");
        if (!(messages instanceof ArrayNode messageArray)
                || messageArray.isEmpty()
                || messageArray.size() > properties.getRequest().getMaxMessages()) {
            throw ApiChatException.invalid(
                    "messages must contain 1 to 256 items.", "messages");
        }
        if (mode == OpenAiRequestPayloadMode.LOOSE_NORMALIZED) {
            MessageProjection messageProjection = projectMessages(messageArray);
            payload.set("messages", messageProjection.messages());
            dropped += messageProjection.droppedFields();
        }
        enforceChatMediaCapabilities(payload.get("messages"), model, "messages");

        boolean stream = booleanValue(payload.get("stream"), "stream", false);
        boolean includeUsage = readIncludeUsage(payload.get("stream_options"));
        long effectiveMax = effectiveTokenLimit(
                payload.get("max_completion_tokens"), "max_completion_tokens",
                payload.get("max_tokens"), "max_tokens", model.maxOutputTokens());

        // 服务端事实覆盖必须在未知字段投影之后执行，透传白名单也不能改写授权模型、账单上限或有状态语义。
        payload.put("model", model.modelName());
        payload.put("stream", stream);
        payload.put("store", false);
        payload.remove(List.of("max_completion_tokens", "max_tokens"));
        payload.put("max_completion_tokens", effectiveMax);
        dropped += countRemovedFields(payload, MEDIA_OUTPUT_FIELDS);
        if (payload.has("web_search_options")
                && !model.capabilities().contains(AiModelCapabilityCode.WEB_SEARCH)) {
            payload.remove("web_search_options");
            dropped++;
        }
        if (!stream) {
            payload.remove("stream_options");
        }

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
        enforceToolBudget(payload.get("tools"), payload.get("functions"));

        return new LooseOpenAiRequestNormalization(
                payload, stream, effectiveMax, includeUsage, mode, dropped);
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

    private MessageProjection projectMessages(ArrayNode source) {
        ArrayNode messages = objectMapper.createArrayNode();
        int dropped = 0;
        for (JsonNode message : source) {
            if (!(message instanceof ObjectNode object)) {
                messages.add(message.deepCopy());
                continue;
            }
            ObjectNode projected = objectMapper.createObjectNode();
            var fields = object.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (MESSAGE_FIELDS.contains(field.getKey())) {
                    projected.set(field.getKey(), field.getValue().deepCopy());
                } else {
                    dropped++;
                }
            }
            messages.add(projected);
        }
        return new MessageProjection(messages, dropped);
    }

    private boolean readIncludeUsage(JsonNode options) {
        if (options == null || options.isNull()) {
            return false;
        }
        if (!(options instanceof ObjectNode object)) {
            throw ApiChatException.invalid(
                    "stream_options must be a JSON object.", "stream_options");
        }
        return booleanValue(object.get("include_usage"),
                "stream_options.include_usage", false);
    }

    private record Projection(ObjectNode payload, int droppedFields) {
    }

    private record MessageProjection(ArrayNode messages, int droppedFields) {
    }
}
