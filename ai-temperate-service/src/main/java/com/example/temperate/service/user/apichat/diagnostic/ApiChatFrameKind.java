package com.example.temperate.service.user.apichat.diagnostic;

/**
 * 该枚举是来记录不含正文的 SSE 帧类别，禁止用枚举值承载模型内容或工具参数。
 */
public enum ApiChatFrameKind {
    DATA,
    OUTPUT,
    USAGE,
    DONE,
    ERROR
}
