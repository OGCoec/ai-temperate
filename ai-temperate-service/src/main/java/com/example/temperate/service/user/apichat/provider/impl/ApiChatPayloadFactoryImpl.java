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
 * 该实现是来保留全部已声明字段及其 JSON 类型、移除空值，并在上游请求中强制 stream=true 与 include_usage=true。
 */
@Service
public final class ApiChatPayloadFactoryImpl implements ApiChatPayloadFactory {

    private final ObjectMapper objectMapper;

    public ApiChatPayloadFactoryImpl(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public ObjectNode create(ValidatedApiChatRequest validated) {
        JsonNode tree = objectMapper.valueToTree(validated.request());
        if (!(tree instanceof ObjectNode payload)) {
            throw new IllegalStateException("Validated API chat request must encode to an object");
        }
        pruneNulls(payload);
        payload.put("model", validated.model().modelName());
        payload.put("stream", true);
        payload.remove(List.of("max_tokens", "max_completion_tokens"));
        payload.put("max_completion_tokens", validated.effectiveMaxOutputTokens());
        ObjectNode streamOptions = payload.with("stream_options");
        streamOptions.put("include_usage", true);
        return payload;
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
