package com.example.temperate.service.user.aiconversation.generation.progress;

/**
 * 描述媒体传输到 OSS 时的可展示状态，完成状态只能在对象写入与校验均成功后发送。
 */
public enum AiConversationMediaUploadState {
    UPLOADING,
    VERIFYING,
    COMPLETED,
    FAILED
}
