package com.example.temperate.service.user.aiconversation.lease;

/**
 * 表示带随机所有者值的 AI 会话租约，释放时必须比较该值以避免误删新租约。
 */
public record AiConversationLease(
        String conversationPublicId,
        AiConversationLeaseType type,
        String owner) {
}
