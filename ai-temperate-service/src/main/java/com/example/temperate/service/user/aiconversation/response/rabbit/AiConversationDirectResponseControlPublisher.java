package com.example.temperate.service.user.aiconversation.response.rabbit;

/**
 * 定义把直接 SSE Stop 控制消息可靠路由到 Owner 实例的发布边界。
 */
public interface AiConversationDirectResponseControlPublisher {

    void publishCancelRequested(
            String requestIdentifier,
            String ownerInstanceId,
            String traceId);
}
