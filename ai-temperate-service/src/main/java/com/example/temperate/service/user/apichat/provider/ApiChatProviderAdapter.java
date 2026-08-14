package com.example.temperate.service.user.apichat.provider;

import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 该策略接口是来按稳定厂商枚举执行能力校验并生成 8317 JSON，新增厂商只增加实现和测试而不修改调用编排。
 */
public interface ApiChatProviderAdapter {

    AiModelProvider type();

    ObjectNode adapt(ValidatedApiChatRequest request);
}
