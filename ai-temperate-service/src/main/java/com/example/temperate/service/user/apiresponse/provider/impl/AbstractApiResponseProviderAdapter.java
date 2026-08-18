package com.example.temperate.service.user.apiresponse.provider.impl;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apiresponse.ApiResponsePayloadFactory;
import com.example.temperate.service.user.apiresponse.ValidatedApiResponseRequest;
import com.example.temperate.service.user.apiresponse.provider.ApiResponseProviderAdapter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.Set;

/**
 * 该基类是来复用 Responses 负载生成与分模式字段过滤，厂商差异由显式策略类型、能力声明和字段集合承担。
 */
abstract class AbstractApiResponseProviderAdapter implements ApiResponseProviderAdapter {

    private final AiModelProvider provider;
    private final ApiResponsePayloadFactory payloadFactory;
    private final Set<String> allowedFields;

    AbstractApiResponseProviderAdapter(
            AiModelProvider provider,
            ApiResponsePayloadFactory payloadFactory,
            Set<String> allowedFields) {
        this.provider = Objects.requireNonNull(provider);
        this.payloadFactory = Objects.requireNonNull(payloadFactory);
        this.allowedFields = Set.copyOf(Objects.requireNonNull(allowedFields));
    }

    @Override
    public final AiModelProvider type() {
        return provider;
    }

    @Override
    public final ObjectNode adapt(ValidatedApiResponseRequest request) {
        return ApiResponseProviderPayloadPolicy.adaptAllowed(
                provider, request, payloadFactory.create(request), allowedFields);
    }
}
