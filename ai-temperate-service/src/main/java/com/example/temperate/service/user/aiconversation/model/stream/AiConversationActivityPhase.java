package com.example.temperate.service.user.aiconversation.model.stream;

/**
 * 定义模型流在下游可以公开展示的稳定活动阶段，不包含供应商私有事件名称。
 */
public enum AiConversationActivityPhase {
    PROCESSING,
    REASONING,
    WEB_SEARCH,
    GENERATING,
    FINALIZING
}
