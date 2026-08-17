package com.example.temperate.service.user.apichat.provider.impl;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.example.temperate.service.user.apichat.provider.ApiChatPayloadFactory;
import com.example.temperate.service.user.apichat.provider.ApiChatProviderAdapter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 该无状态适配器是来校验并生成 XAI 厂商的 8317 Chat Completions 核心请求，不静默丢弃客户端字段。
 */
@Component
public final class XaiApiChatProviderAdapter implements ApiChatProviderAdapter {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "model", "messages", "stream", "stream_options",
            "max_completion_tokens", "temperature", "top_p",
            "reasoning_effort", "prompt_cache_key", "store", "service_tier",
            "presence_penalty", "frequency_penalty", "stop", "seed", "n",
            "tools", "tool_choice", "parallel_tool_calls");
    private final ApiChatPayloadFactory payloadFactory;

    public XaiApiChatProviderAdapter(ApiChatPayloadFactory payloadFactory) {
        this.payloadFactory = Objects.requireNonNull(payloadFactory);
    }

    @Override
    public AiModelProvider type() {
        return AiModelProvider.XAI;
    }

    @Override
    public ObjectNode adapt(ValidatedApiChatRequest request) {
        return ApiChatProviderPayloadPolicy.requireAllowed(
                type(), request, payloadFactory.create(request), ALLOWED_FIELDS);
    }
}
