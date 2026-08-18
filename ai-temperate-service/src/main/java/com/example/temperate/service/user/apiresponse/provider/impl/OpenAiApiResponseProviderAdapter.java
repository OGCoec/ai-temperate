package com.example.temperate.service.user.apiresponse.provider.impl;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apiresponse.ApiResponsePayloadFactory;
import org.springframework.stereotype.Component;

/** 该策略是来为 OpenAI 厂商模型生成 8317 Responses 兼容负载。 */
@Component
public final class OpenAiApiResponseProviderAdapter
        extends AbstractApiResponseProviderAdapter {

    public OpenAiApiResponseProviderAdapter(ApiResponsePayloadFactory payloadFactory) {
        super(AiModelProvider.OPENAI, payloadFactory);
    }
}
