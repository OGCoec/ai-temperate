package com.example.temperate.service.user.aiinference.api;

/**
 * 该枚举是来标识公开 API Key 推理请求采用的稳定协议，供计费、并发和低基数指标共享而不依赖具体 Controller。
 */
public enum ApiInferenceProtocol {
    CHAT_COMPLETIONS,
    RESPONSES
}
