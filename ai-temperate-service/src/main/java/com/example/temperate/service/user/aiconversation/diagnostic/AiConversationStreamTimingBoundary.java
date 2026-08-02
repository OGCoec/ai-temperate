package com.example.temperate.service.user.aiconversation.diagnostic;

/**
 * 定义模型原始流到业务 SSE 就绪之间需要独立测量的四个只读时序边界。
 */
public enum AiConversationStreamTimingBoundary {
    SPRING_AI_RAW,
    AFTER_BOUNDED_ELASTIC,
    AFTER_STREAM_BATCHER,
    SSE_EVENT_READY
}
