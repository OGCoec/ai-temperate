package com.example.temperate.web.user.aiconversation.api;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationPreupload;
import java.time.Instant;
import java.util.Map;

/**
 * 表示前端直接 PUT 到 OSS 时需要的单个附件签名条件。
 */
public record AiConversationPreuploadFileResponse(
        String attachmentId,
        String fileName,
        String contentType,
        String sizeBytes,
        String uploadUrl,
        String method,
        Map<String, String> uploadHeaders,
        Instant expiresAt) {

    public static AiConversationPreuploadFileResponse from(
            AiConversationPreupload value) {
        return new AiConversationPreuploadFileResponse(
                value.attachmentId(),
                value.fileName(),
                value.contentType(),
                value.sizeBytes(),
                value.uploadUrl(),
                value.method(),
                value.uploadHeaders(),
                value.expiresAt());
    }
}
