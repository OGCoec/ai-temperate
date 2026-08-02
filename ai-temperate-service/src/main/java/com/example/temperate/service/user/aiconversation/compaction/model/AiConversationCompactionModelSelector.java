package com.example.temperate.service.user.aiconversation.compaction.model;

/**
 * 定义根据规范会话公开 ID 从当前启用集合稳定选择一个压缩模型的边界。
 */
public interface AiConversationCompactionModelSelector {

    AiConversationCompactionModelRef selectRequired(String conversationPublicId);
}
