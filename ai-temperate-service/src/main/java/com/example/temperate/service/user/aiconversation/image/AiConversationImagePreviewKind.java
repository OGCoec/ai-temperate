package com.example.temperate.service.user.aiconversation.image;

/**
 * 区分 SSE 中已经可作为最终显示的小原图与仍需在 OSS 就绪后升级的压缩缩略图。
 */
public enum AiConversationImagePreviewKind {
    FULL,
    THUMBNAIL
}
