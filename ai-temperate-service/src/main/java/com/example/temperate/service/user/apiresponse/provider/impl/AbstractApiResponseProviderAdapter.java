package com.example.temperate.service.user.apiresponse.provider.impl;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apiresponse.ApiResponsePayloadFactory;
import com.example.temperate.service.user.apiresponse.ValidatedApiResponseRequest;
import com.example.temperate.service.user.apiresponse.provider.ApiResponseProviderAdapter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * 该基类是来复用 OpenAI 兼容 Responses 负载生成步骤，厂商差异由显式策略类型和模型能力开关承担。
 */
abstract class AbstractApiResponseProviderAdapter implements ApiResponseProviderAdapter {

    private final AiModelProvider provider;
    private final ApiResponsePayloadFactory payloadFactory;

    AbstractApiResponseProviderAdapter(
            AiModelProvider provider,
            ApiResponsePayloadFactory payloadFactory) {
        this.provider = Objects.requireNonNull(provider);
        this.payloadFactory = Objects.requireNonNull(payloadFactory);
    }

    @Override
    public final AiModelProvider type() {
        return provider;
    }

    @Override
    public final ObjectNode adapt(ValidatedApiResponseRequest request) {
        return payloadFactory.create(request);
    }
}
