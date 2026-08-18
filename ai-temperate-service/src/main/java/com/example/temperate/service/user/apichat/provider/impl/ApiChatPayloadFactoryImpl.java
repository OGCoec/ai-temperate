package com.example.temperate.service.user.apichat.provider.impl;

import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.example.temperate.service.user.apichat.provider.ApiChatPayloadFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 该实现是来让旧厂商路径继续扁平化文本块，并让 OpenAI 增强路径保留原始结构后只覆盖授权、Token 与结算所需字段。
 */
@Service
public final class ApiChatPayloadFactoryImpl implements ApiChatPayloadFactory {

    private final ObjectMapper objectMapper;

    public ApiChatPayloadFactoryImpl(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public ObjectNode create(ValidatedApiChatRequest validated) {
        JsonNode tree = validated.normalizedPayload() == null
                ? objectMapper.valueToTree(validated.request())
                : validated.normalizedPayload().deepCopy();
        if (!(tree instanceof ObjectNode payload)) {
            throw new IllegalStateException("Validated API chat request must encode to an object");
        }
        if (!validated.openAiEnhanced()) {
            pruneNulls(payload);
            flattenTextContentParts(payload);
        }
        payload.put("model", validated.model().modelName());
        payload.put("stream", validated.stream());
        if (validated.openAiEnhanced() && payload.hasNonNull("max_tokens")) {
            payload.remove("max_completion_tokens");
            payload.put("max_tokens", validated.effectiveMaxOutputTokens());
        } else {
            payload.remove(List.of("max_tokens", "max_completion_tokens"));
            payload.put("max_completion_tokens", validated.effectiveMaxOutputTokens());
        }
        if (validated.stream()) {
            JsonNode existingOptions = payload.get("stream_options");
            ObjectNode streamOptions;
            if (existingOptions instanceof ObjectNode object) {
                streamOptions = object;
            } else {
                // 校验器只允许缺省、null 或对象；显式 null 在这里规范化为服务端结算所需对象。
                payload.remove("stream_options");
                streamOptions = payload.putObject("stream_options");
            }
            streamOptions.put("include_usage", true);
        } else {
            payload.remove("stream_options");
        }
        return payload;
    }

    private static void flattenTextContentParts(ObjectNode payload) {
        JsonNode messages = payload.get("messages");
        if (!(messages instanceof ArrayNode messageArray)) {
            return;
        }
        for (JsonNode message : messageArray) {
            if (!(message instanceof ObjectNode messageObject)
                    || !(messageObject.get("content") instanceof ArrayNode contentParts)) {
                continue;
            }
            StringBuilder flattened = new StringBuilder();
            for (JsonNode part : contentParts) {
                JsonNode text = part.get("text");
                if (text == null || !text.isTextual()) {
                    throw new IllegalStateException(
                            "Validated text content part must contain string text");
                }
                flattened.append(text.textValue());
            }
            // 文本块的结构边界不应凭空注入字符；按原顺序直接拼接才能保留客户端提交的全部文本字节。
            messageObject.put("content", flattened.toString());
        }
    }

    private static void pruneNulls(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> remove = new ArrayList<>();
            object.fields().forEachRemaining(entry -> {
                if (entry.getValue() == null || entry.getValue().isNull()) {
                    remove.add(entry.getKey());
                } else {
                    pruneNulls(entry.getValue());
                }
            });
            object.remove(remove);
        } else if (node instanceof ArrayNode array) {
            array.forEach(ApiChatPayloadFactoryImpl::pruneNulls);
        }
    }
}
