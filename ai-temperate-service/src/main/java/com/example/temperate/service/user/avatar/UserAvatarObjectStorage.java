package com.example.temperate.service.user.avatar;

import java.time.Instant;
import java.util.Map;

/**
 * 定义头像 Service 使用的最小对象存储端口，隔离阿里云 SDK 与业务编排。
 */
public interface UserAvatarObjectStorage {

    PresignedPut generatePresignedPutUrl(String objectKey, String contentType);

    AvatarObjectMetadata headObject(String objectKey);

    byte[] downloadObjectBytesBounded(String objectKey, long maximumBytes);

    String copyObjectToPublic(
            String sourceObjectKey,
            String destinationObjectKey,
            String contentType);

    void deleteObject(String objectKey);

    /**
     * 表示预签名 PUT URL 和所有必须原样发送的签名请求头。
     */
    record PresignedPut(
            String uploadUrl,
            Instant expiresAt,
            Map<String, String> headers) {
    }

    /**
     * 表示 OSS 中不存在目标对象。
     */
    final class ObjectNotFoundException extends RuntimeException {

        public ObjectNotFoundException(Throwable cause) {
            super("OSS object was not found", cause);
        }
    }

    /**
     * 表示对象体超过业务允许的有界读取上限。
     */
    final class ObjectTooLargeException extends RuntimeException {

        public ObjectTooLargeException(Throwable cause) {
            super("OSS object exceeds the avatar byte limit", cause);
        }
    }

    /**
     * 表示对象存储当前不可用或 SDK 请求失败。
     */
    final class StorageException extends RuntimeException {

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
