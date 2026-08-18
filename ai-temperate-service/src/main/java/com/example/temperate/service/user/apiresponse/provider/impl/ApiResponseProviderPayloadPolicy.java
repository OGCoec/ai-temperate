package com.example.temperate.service.user.apiresponse.provider.impl;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apiresponse.ValidatedApiResponseRequest;
import com.example.temperate.service.user.openaicompatibility.OpenAiRequestPayloadMode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * 该策略辅助器是来校验 Responses 厂商能力，并在宽松与受控透传模式下静默删除该厂商明确不支持的已知可选字段。
 */
final class ApiResponseProviderPayloadPolicy {

    private static final Set<String> KNOWN_FIELDS = Set.of(
            "model", "input", "instructions", "stream", "max_output_tokens",
            "reasoning", "tools", "tool_choice", "parallel_tool_calls",
            "max_tool_calls", "text", "temperature", "top_p", "top_logprobs",
            "service_tier", "truncation", "safety_identifier", "user", "include",
            "prompt_cache_key", "prompt_cache_retention", "client_metadata", "store",
            "background", "previous_response_id", "conversation", "metadata");

    private ApiResponseProviderPayloadPolicy() {
    }

    static ObjectNode adaptAllowed(
            AiModelProvider provider,
            ValidatedApiResponseRequest request,
            ObjectNode payload,
            Set<String> allowedTopLevelFields) {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(request);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(allowedTopLevelFields);
        if (!provider.vendor().equalsIgnoreCase(request.model().vendor())
                || !request.model().capabilities()
                .contains(AiModelCapabilityCode.RESPONSES)) {
            throw ApiChatException.invalid("Model provider capability mismatch.", "model");
        }
        if (request.payloadMode() == OpenAiRequestPayloadMode.STRICT_DTO) {
            return payload;
        }
        Iterator<String> fields = payload.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (allowedTopLevelFields.contains(field)) {
                continue;
            }
            if (request.payloadMode() == OpenAiRequestPayloadMode.LOOSE_NORMALIZED
                    || KNOWN_FIELDS.contains(field)) {
                // 未列入公共字段目录的厂商扩展仅在受控透传模式保留，状态字段已在规范化阶段被强制删除。
                fields.remove();
            }
        }
        return payload;
    }
}
