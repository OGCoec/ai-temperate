package com.example.temperate.service.user.aiconversation.image;

/**
 * 区分只供当前实例实时展示的中间图和唯一允许进入 OSS 持久化流程的最终图。
 */
public enum AiConversationGeneratedImagePhase {
    PARTIAL,
    FINAL
}
