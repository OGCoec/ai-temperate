package com.example.temperate.service.user.aiconversation.attachment;

import java.time.Instant;
import java.util.Map;

/**
 * 定义会话附件业务所需的最小 OSS 端口，隔离阿里云 SDK、Bucket 配置和业务编排。
 */
public interface AiConversationAttachmentObjectStorage {

    PresignedPut generatePresignedPut(
            String objectKey,
            String contentType,
            long contentLength);

    PresignedGet generatePresignedGet(String objectKey);

    ObjectMetadata headObject(String objectKey);

    String copyToPublic(String sourceObjectKey, String destinationObjectKey, String contentType);

    String putPublic(
            String destinationObjectKey,
            byte[] bytes,
            String contentType);

    void deleteObject(String objectKey);

    record PresignedPut(
            String uploadUrl,
            String method,
            Map<String, String> headers,
            Instant expiresAt) {
    }

    record PresignedGet(String downloadUrl, Instant expiresAt) {
    }

    record ObjectMetadata(long sizeBytes, String contentType) {
    }

    final class ObjectNotFoundException extends RuntimeException {

        public ObjectNotFoundException(Throwable cause) {
            super("Conversation attachment object was not found", cause);
        }
    }

    final class StorageException extends RuntimeException {

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
