package com.example.temperate.service.user.apiresponse.impl;

import com.example.temperate.service.user.apiresponse.ApiResponsePayloadFactory;
import com.example.temperate.service.user.apiresponse.ValidatedApiResponseRequest;
import com.example.temperate.service.user.openaicompatibility.OpenAiRequestPayloadMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 该实现是来删除缺省空值并使用数据库规范模型名构造上游负载，客户端 Bearer Key 从不参与该转换。
 */
@Service
public final class ApiResponsePayloadFactoryImpl implements ApiResponsePayloadFactory {

    private final ObjectMapper objectMapper;

    public ApiResponsePayloadFactoryImpl(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public ObjectNode create(ValidatedApiResponseRequest request) {
        ObjectNode payload = request.normalizedPayload() == null
                ? objectMapper.valueToTree(request.request())
                : request.normalizedPayload().deepCopy();
        if (request.payloadMode() == OpenAiRequestPayloadMode.STRICT_DTO) {
            removeNulls(payload);
            removeEmptyNestedObject(payload, "text", "format");
            removeEmptyObject(payload, "reasoning");
            removeEmptyObject(payload, "text");
            boolean hasTools = hasNonEmptyArray(payload, "tools");
            removeEmptyArray(payload, "tools");
            removeEmptyArray(payload, "include");
            if (!hasTools) {
                // 旧 DTO 会无条件序列化默认工具控制值；只在旧路径删除，增强路径保留所有已验证字段。
                payload.remove("tool_choice");
                payload.remove("parallel_tool_calls");
            }
        }
        // 这些字段是授权、上下文校验和账单预扣共同使用的规范值，禁止保留客户端原始取值。
        payload.put("model", request.model().modelName());
        payload.put("stream", request.stream());
        payload.put("store", false);
        payload.put("max_output_tokens", request.effectiveMaxOutputTokens());
        return payload;
    }

    private static boolean hasNonEmptyArray(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        return value != null && value.isArray() && !value.isEmpty();
    }

    private static void removeEmptyArray(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value != null && value.isArray() && value.isEmpty()) {
            payload.remove(field);
        }
    }

    private static void removeEmptyNestedObject(
            ObjectNode payload,
            String parentField,
            String childField) {
        JsonNode parent = payload.get(parentField);
        if (parent instanceof ObjectNode object) {
            removeEmptyObject(object, childField);
        }
    }

    private static void removeEmptyObject(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value != null && value.isObject() && value.isEmpty()) {
            payload.remove(field);
        }
    }

    private static void removeNulls(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue() == null || field.getValue().isNull()) {
                    fields.remove();
                } else {
                    removeNulls(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(ApiResponsePayloadFactoryImpl::removeNulls);
        }
    }
}
