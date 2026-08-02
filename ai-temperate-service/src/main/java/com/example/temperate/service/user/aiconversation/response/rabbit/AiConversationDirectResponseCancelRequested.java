package com.example.temperate.service.user.aiconversation.response.rabbit;

/**
 * 承载直接 SSE 跨实例 Stop 所需的受保护请求标识，不包含用户 ID、UUID 或模型正文。
 */
public record AiConversationDirectResponseCancelRequested(
        String requestIdentifier) {

    public AiConversationDirectResponseCancelRequested {
        if (requestIdentifier == null
                || !requestIdentifier.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException(
                    "AI direct response request identifier is invalid.");
        }
    }
}
