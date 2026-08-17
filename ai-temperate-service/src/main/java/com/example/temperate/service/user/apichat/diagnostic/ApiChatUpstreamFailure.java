package com.example.temperate.service.user.apichat.diagnostic;

/**
 * 该枚举是来分类不属于 SSE 结构偏差的上游传输失败，避免把可能含 URL 或响应片段的第三方异常消息写入因果链。
 */
public enum ApiChatUpstreamFailure {
    CONNECTION_FAILURE,
    MAXIMUM_DURATION_EXCEEDED
}
