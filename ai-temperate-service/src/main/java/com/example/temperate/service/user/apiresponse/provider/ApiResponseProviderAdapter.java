package com.example.temperate.service.user.apiresponse.provider;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apiresponse.ValidatedApiResponseRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 该策略接口是来按稳定厂商枚举生成 OpenAI Responses 兼容负载，具体可用性仍由模型 RESPONSES 能力决定。
 */
public interface ApiResponseProviderAdapter {

    AiModelProvider type();

    ObjectNode adapt(ValidatedApiResponseRequest request);
}
