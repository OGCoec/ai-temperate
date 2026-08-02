package com.example.temperate.service.user.aiconversation.attachment;

/**
 * 对会话附件进行稳定的粗粒度分类，用于模型能力校验和前端安全展示策略选择。
 */
public enum AiConversationAttachmentCategory {
    IMAGE,
    AUDIO,
    VIDEO,
    DOCUMENT,
    ARCHIVE,
    OTHER
}
