package com.example.temperate.service.user.apiresponse.provider.impl;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apiresponse.ApiResponsePayloadFactory;
import org.springframework.stereotype.Component;

/** 该策略是来为声明 RESPONSES 能力的 Anthropic 厂商模型生成 8317 兼容负载。 */
@Component
public final class AnthropicApiResponseProviderAdapter
        extends AbstractApiResponseProviderAdapter {

    public AnthropicApiResponseProviderAdapter(ApiResponsePayloadFactory payloadFactory) {
        super(AiModelProvider.ANTHROPIC, payloadFactory);
    }
}
