package com.example.temperate.service.user.openaicompatibility;

/**
 * 该枚举是来标识公共宽松兼容层正在规范化的 OpenAI 风格入口，供策略 Registry 使用稳定键选择实现。
 */
public enum OpenAiCompatibilityProtocol {
    CHAT_COMPLETIONS,
    RESPONSES
}
