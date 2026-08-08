package com.example.temperate.service.user.aiconversation.video;

/**
 * 定义视频异步任务在 xAI、FC 与 OSS 三个边界之间可持久化的安全阶段，不包含临时下载地址或授权信息。
 */
public enum AiConversationVideoGenerationStage {
    QUEUED,
    VALIDATING_MEDIA,
    RESERVED,
    XAI_SUBMITTING,
    XAI_PENDING,
    XAI_DONE,
    OSS_TRANSFERRING,
    OSS_READY,
    SUCCEEDED,
    MEDIA_VALIDATION_FAILED,
    XAI_REJECTED,
    XAI_FAILED,
    XAI_EXPIRED,
    XAI_RESULT_UNCERTAIN,
    OSS_TRANSFER_FAILED,
    BILLING_RECONCILE_REQUIRED
}
