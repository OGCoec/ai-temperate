package com.example.temperate.service.user.aiconversation.attachment.impl;

import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.aliyun.sdk.service.oss2.progress.ProgressListener;
import com.example.temperate.common.aliyun.AliyunOssUtils;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentObjectStorage;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentProgressObjectStorage;
import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 使用项目通用阿里云 OSS 工具实现会话附件预签名、校验、复制、上传和补偿删除。
 */
@Service
public final class AliyunAiConversationAttachmentObjectStorageImpl
        implements AiConversationAttachmentObjectStorage,
        AiConversationAttachmentProgressObjectStorage {

    private static final String IMMUTABLE_CACHE_CONTROL =
            "public, max-age=31536000, immutable";

    private final AliyunOssUtils ossUtils;
    private final AiConversationAttachmentProperties properties;

    public AliyunAiConversationAttachmentObjectStorageImpl(
            AliyunOssUtils ossUtils,
            AiConversationAttachmentProperties properties) {
        this.ossUtils = Objects.requireNonNull(ossUtils);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public PresignedPut generatePresignedPut(
            String objectKey,
            String contentType,
            long contentLength) {
        try {
            var signed = ossUtils.generatePresignedPutUrl(
                    properties.bucket(),
                    properties.region(),
                    properties.endpoint(),
                    objectKey,
                    contentType,
                    contentLength,
                    properties.presignedPutTtl());
            // 客户端必须逐字发送 SDK 实际签入 URL 的 Header；手工重建可能与签名版本产生偏差。
            Map<String, String> headers = signed.signedHeaders();
            return new PresignedPut(
                    signed.url(),
                    "PUT",
                    Map.copyOf(headers),
                    signed.expiresAt());
        } catch (RuntimeException exception) {
            throw map("OSS pre-sign failed", exception);
        }
    }

    @Override
    public PresignedGet generatePresignedGet(String objectKey) {
        try {
            var signed = ossUtils.generatePresignedGetUrl(
                    properties.bucket(),
                    properties.region(),
                    properties.endpoint(),
                    objectKey,
                    properties.signedGetTtl());
            return new PresignedGet(signed.url(), signed.expiresAt());
        } catch (RuntimeException exception) {
            throw map("OSS GET pre-sign failed", exception);
        }
    }

    @Override
    public ObjectMetadata headObject(String objectKey) {
        try {
            var metadata = ossUtils.headObject(
                    properties.bucket(),
                    properties.region(),
                    properties.endpoint(),
                    objectKey);
            return new ObjectMetadata(
                    metadata.contentLength() == null ? -1L : metadata.contentLength(),
                    metadata.contentType());
        } catch (RuntimeException exception) {
            throw map("OSS HEAD failed", exception);
        }
    }

    @Override
    public String copyToPublic(
            String sourceObjectKey,
            String destinationObjectKey,
            String contentType) {
        try {
            ossUtils.copyObjectToBucket(
                    properties.bucket(),
                    sourceObjectKey,
                    properties.bucket(),
                    destinationObjectKey,
                    properties.region(),
                    properties.endpoint(),
                    contentType);
            return properties.publicBaseUrl() + "/" + destinationObjectKey;
        } catch (RuntimeException exception) {
            throw map("OSS copy failed", exception);
        }
    }

    @Override
    public String putPublic(
            String destinationObjectKey,
            byte[] bytes,
            String contentType) {
        return putPublic(destinationObjectKey, bytes, contentType, null);
    }

    @Override
    public String putPublic(
            String destinationObjectKey,
            byte[] bytes,
            String contentType,
            ProgressListener progressListener) {
        try {
            ossUtils.putObjectBytes(
                    properties.bucket(),
                    properties.region(),
                    properties.endpoint(),
                    destinationObjectKey,
                    bytes,
                    contentType,
                    IMMUTABLE_CACHE_CONTROL,
                    true,
                    properties.uploadConnectTimeout(),
                    properties.uploadReadWriteTimeout(),
                    progressListener);
            return properties.publicBaseUrl() + "/" + destinationObjectKey;
        } catch (RuntimeException exception) {
            throw map("OSS upload failed", exception);
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        try {
            ossUtils.deleteObject(
                    properties.bucket(),
                    properties.region(),
                    properties.endpoint(),
                    objectKey);
        } catch (RuntimeException exception) {
            throw map("OSS delete failed", exception);
        }
    }

    private static RuntimeException map(String message, RuntimeException exception) {
        Throwable root = rootCause(exception);
        if (root instanceof ServiceException serviceException
                && serviceException.statusCode() == 404) {
            return new ObjectNotFoundException(exception);
        }
        return new StorageException(message, exception);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
