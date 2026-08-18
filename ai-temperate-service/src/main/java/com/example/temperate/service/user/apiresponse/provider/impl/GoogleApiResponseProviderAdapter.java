package com.example.temperate.service.user.apiresponse.provider.impl;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apiresponse.ApiResponsePayloadFactory;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 该策略是来为声明 RESPONSES 能力的 Google 厂商模型生成 8317 兼容负载。 */
@Component
public final class GoogleApiResponseProviderAdapter
        extends AbstractApiResponseProviderAdapter {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "model", "input", "instructions", "stream", "store", "max_output_tokens",
            "reasoning", "tools", "tool_choice", "parallel_tool_calls",
            "max_tool_calls", "text", "temperature", "top_p", "service_tier",
            "truncation", "prompt_cache_key", "safety_identifier", "user", "include");

    public GoogleApiResponseProviderAdapter(ApiResponsePayloadFactory payloadFactory) {
        super(AiModelProvider.GOOGLE, payloadFactory, ALLOWED_FIELDS);
    }
}
