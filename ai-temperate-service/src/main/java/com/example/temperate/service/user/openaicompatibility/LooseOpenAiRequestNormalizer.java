package com.example.temperate.service.user.openaicompatibility;

/**
 * 该策略接口是来按公开协议规范化 OpenAI 风格请求，使新增协议实现只需注册新策略而不修改调用流程。
 */
public interface LooseOpenAiRequestNormalizer {

    OpenAiCompatibilityProtocol protocol();

    LooseOpenAiRequestNormalization normalize(LooseOpenAiRequestContext context);
}
