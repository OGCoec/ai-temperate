package com.example.temperate.service.user.aiconversation.attachment;

import java.util.Objects;

/**
 * 表示可进入会话上下文或数据库 JSONB 的通用附件元数据，不承载文件二进制和 OSS 凭据。
 *
 * <p>正式附件的 {@code url} 是完整 HTTPS 公网地址；流式处理中断前的临时附件使用仅服务端可解析的
 * {@code ait-temp:} 定位符，模型调用前再临时换取签名 GET URL，避免把签名 URL 写入 Redis。</p>
 */
public record AiConversationAttachment(
        int schemaVersion,
        String attachmentId,
        String fileName,
        String contentType,
        String sizeBytes,
        AiConversationAttachmentCategory category,
        String url,
        AiConversationAttachmentState state,
        String failureCode) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String STORAGE_FAILURE_CODE = "OSS_PERSIST_FAILED";

    public AiConversationAttachment {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported attachment schema version");
        }
        attachmentId = requireText(attachmentId, "attachmentId");
        fileName = requireText(fileName, "fileName");
        contentType = requireText(contentType, "contentType");
        sizeBytes = requireText(sizeBytes, "sizeBytes");
        category = Objects.requireNonNull(category, "category must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        if (state == AiConversationAttachmentState.AVAILABLE) {
            url = requireText(url, "url");
            if (failureCode != null) {
                throw new IllegalArgumentException("Available attachment cannot have a failure code");
            }
        } else {
            url = null;
            if (!STORAGE_FAILURE_CODE.equals(failureCode)) {
                throw new IllegalArgumentException("Storage failure must use the fixed failure code");
            }
        }
    }

    public static AiConversationAttachment available(
            String attachmentId,
            String fileName,
            String contentType,
            long sizeBytes,
            AiConversationAttachmentCategory category,
            String url) {
        return new AiConversationAttachment(
                CURRENT_SCHEMA_VERSION,
                attachmentId,
                fileName,
                contentType,
                Long.toString(sizeBytes),
                category,
                url,
                AiConversationAttachmentState.AVAILABLE,
                null);
    }

    public static AiConversationAttachment storageFailed(
            String attachmentId,
            String fileName,
            String contentType,
            long sizeBytes,
            AiConversationAttachmentCategory category) {
        return new AiConversationAttachment(
                CURRENT_SCHEMA_VERSION,
                attachmentId,
                fileName,
                contentType,
                Long.toString(sizeBytes),
                category,
                null,
                AiConversationAttachmentState.STORAGE_FAILED,
                STORAGE_FAILURE_CODE);
    }

    public long sizeBytesAsLong() {
        try {
            long value = Long.parseLong(sizeBytes);
            if (value <= 0L) {
                throw new IllegalArgumentException("Attachment size must be positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Attachment size is not a decimal long", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
