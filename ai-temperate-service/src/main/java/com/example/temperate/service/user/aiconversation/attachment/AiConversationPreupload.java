package com.example.temperate.service.user.aiconversation.attachment;

import java.time.Instant;
import java.util.Map;

/**
 * 表示单个会话附件的预签名 PUT 条件，客户端必须原样携带返回的请求头。
 */
public record AiConversationPreupload(
        String attachmentId,
        String fileName,
        String contentType,
        String sizeBytes,
        String uploadUrl,
        String method,
        Map<String, String> uploadHeaders,
        Instant expiresAt) {
}
