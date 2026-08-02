package com.example.temperate.service.user.aiconversation.attachment;

/**
 * 表示会话附件最终是否已经成功持久化到正式 OSS 路径。
 */
public enum AiConversationAttachmentState {
    AVAILABLE,
    STORAGE_FAILED
}
