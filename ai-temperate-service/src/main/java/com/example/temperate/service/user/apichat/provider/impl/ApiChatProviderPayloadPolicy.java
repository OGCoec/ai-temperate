package com.example.temperate.service.user.apichat.provider.impl;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.example.temperate.service.user.openaicompatibility.OpenAiRequestPayloadMode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * 该策略辅助器是来执行厂商能力校验和分模式字段过滤：严格路径保持报错，普通宽松路径静默过滤，受控透传只过滤已知不兼容字段。
 */
final class ApiChatProviderPayloadPolicy {

    private static final Set<String> KNOWN_FIELDS = Set.of(
            "model", "messages", "stream", "stream_options",
            "max_completion_tokens", "max_tokens", "temperature", "top_p",
            "presence_penalty", "frequency_penalty", "stop", "seed", "n",
            "reasoning_effort", "service_tier", "verbosity", "safety_identifier",
            "user", "logprobs", "top_logprobs", "prediction", "prompt_cache_key",
            "prompt_cache_options", "tools", "tool_choice", "parallel_tool_calls",
            "functions", "function_call", "response_format", "store", "metadata",
            "web_search_options", "modalities", "audio");

    private ApiChatProviderPayloadPolicy() {
    }

    static ObjectNode adaptAllowed(
            AiModelProvider provider,
            ValidatedApiChatRequest request,
            ObjectNode payload,
            Set<String> allowedTopLevelFields) {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(request);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(allowedTopLevelFields);
        if (!provider.vendor().equalsIgnoreCase(request.model().vendor())
                || !request.model().capabilities()
                .contains(AiModelCapabilityCode.CHAT_COMPLETIONS)) {
            throw ApiChatException.invalid("Model provider capability mismatch.", "model");
        }
        Iterator<String> fields = payload.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowedTopLevelFields.contains(field)) {
                if (request.payloadMode() == OpenAiRequestPayloadMode.STRICT_DTO) {
                    throw ApiChatException.invalid(
                            "The model provider does not support this request field.",
                            field);
                }
                if (request.payloadMode() == OpenAiRequestPayloadMode.LOOSE_NORMALIZED
                        || KNOWN_FIELDS.contains(field)) {
                    // 透传模式只保留不在公共目录内的厂商扩展，已知不兼容字段仍由对应 Adapter 静默移除。
                    fields.remove();
                }
            }
        }
        return payload;
    }
}
