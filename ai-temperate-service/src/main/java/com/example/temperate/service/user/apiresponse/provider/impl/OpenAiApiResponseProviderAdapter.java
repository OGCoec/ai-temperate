package com.example.temperate.service.user.apiresponse.provider.impl;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apiresponse.ApiResponsePayloadFactory;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 该策略是来为 OpenAI 厂商模型生成 8317 Responses 兼容负载。 */
@Component
public final class OpenAiApiResponseProviderAdapter
        extends AbstractApiResponseProviderAdapter {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "model", "input", "instructions", "stream", "max_output_tokens",
            "reasoning", "tools", "tool_choice", "parallel_tool_calls",
            "max_tool_calls", "text", "temperature", "top_p", "top_logprobs",
            "service_tier", "truncation", "safety_identifier", "user", "include",
            "prompt_cache_key", "prompt_cache_retention", "client_metadata", "store",
            "metadata");

    public OpenAiApiResponseProviderAdapter(ApiResponsePayloadFactory payloadFactory) {
        super(AiModelProvider.OPENAI, payloadFactory, ALLOWED_FIELDS);
    }
}
