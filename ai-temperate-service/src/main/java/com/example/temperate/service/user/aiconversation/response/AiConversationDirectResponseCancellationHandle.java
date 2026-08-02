package com.example.temperate.service.user.aiconversation.response;

import com.example.temperate.service.user.aiconversation.context.AiConversationInterruptionSource;

/**
 * 定义本实例直接 SSE 上游订阅的幂等取消句柄，并在取消前冻结中断来源。
 */
@FunctionalInterface
public interface AiConversationDirectResponseCancellationHandle {

    void cancel(AiConversationInterruptionSource interruptionSource);
}
