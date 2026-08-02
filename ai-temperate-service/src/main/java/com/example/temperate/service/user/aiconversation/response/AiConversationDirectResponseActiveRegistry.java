package com.example.temperate.service.user.aiconversation.response;

import com.example.temperate.service.user.aiconversation.context.AiConversationInterruptionSource;

/**
 * 定义本实例按受保护幂等标识保存直接 SSE 取消句柄的线程安全边界。
 */
public interface AiConversationDirectResponseActiveRegistry {

    void register(
            String requestIdentifier,
            AiConversationDirectResponseCancellationHandle cancellationHandle);

    boolean cancel(
            String requestIdentifier,
            AiConversationInterruptionSource interruptionSource);

    boolean isActive(String requestIdentifier);

    void remove(
            String requestIdentifier,
            AiConversationDirectResponseCancellationHandle cancellationHandle);
}
