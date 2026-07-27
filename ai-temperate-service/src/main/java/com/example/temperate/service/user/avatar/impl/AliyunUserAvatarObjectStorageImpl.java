package com.example.temperate.service.user.avatar.impl;

import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.example.temperate.common.aliyun.AliyunOssUtils;
import com.example.temperate.service.user.avatar.AvatarObjectMetadata;
import com.example.temperate.service.user.avatar.UserAvatarObjectStorage;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 使用项目通用阿里云 OSS 工具实现头像对象存储端口。
 *
 * <p>该实现固定使用头像 Bucket 配置并构造公开 URL，不决定用户路径、NanoID 或图片格式。</p>
 */
@Service
public final class AliyunUserAvatarObjectStorageImpl implements UserAvatarObjectStorage {

    private final AliyunOssUtils ossUtils;
    private final String bucket;
    private final String region;
    private final String endpoint;
    private final String publicBaseUrl;
    private final Duration presignedUrlTtl;

    public AliyunUserAvatarObjectStorageImpl(
            AliyunOssUtils ossUtils,
            @Value("${user-avatar.oss.bucket}") String bucket,
            @Value("${user-avatar.oss.region}") String region,
            @Value("${user-avatar.oss.endpoint}") String endpoint,
            @Value("${user-avatar.oss.public-base-url}") String publicBaseUrl,
            @Value("${user-avatar.oss.presigned-url-ttl:PT10M}") Duration presignedUrlTtl) {
        this.ossUtils = ossUtils;
        this.bucket = requireText(bucket, "bucket");
        this.region = requireText(region, "region");
        this.endpoint = requireHttpsUrl(endpoint, "endpoint");
        this.publicBaseUrl = stripTrailingSlash(
                requireHttpsUrl(publicBaseUrl, "publicBaseUrl"));
        if (presignedUrlTtl == null
                || presignedUrlTtl.isZero()
                || presignedUrlTtl.isNegative()) {
            throw new IllegalArgumentException("presignedUrlTtl must be positive");
        }
        this.presignedUrlTtl = presignedUrlTtl;
    }

    @Override
    public PresignedPut generatePresignedPutUrl(String objectKey, String contentType) {
        try {
            var signed = ossUtils.generatePresignedPutUrl(
                    bucket,
                    region,
                    endpoint,
                    objectKey,
                    contentType,
                    presignedUrlTtl);
            // 客户端必须发送与签名请求完全相同的安全头；固定输出键名可消除 SDK 大小写差异。
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", contentType);
            headers.put("x-oss-object-acl", "private");
            headers.put("x-oss-forbid-overwrite", "true");
            return new PresignedPut(
                    signed.url(),
                    signed.expiresAt(),
                    Map.copyOf(headers));
        } catch (RuntimeException exception) {
            throw mapStorageFailure("OSS pre-sign failed", exception);
        }
    }

    @Override
    public AvatarObjectMetadata headObject(String objectKey) {
        try {
            var metadata = ossUtils.headObject(bucket, region, endpoint, objectKey);
            long size = metadata.contentLength() == null ? -1L : metadata.contentLength();
            return new AvatarObjectMetadata(size, metadata.contentType());
        } catch (RuntimeException exception) {
            throw mapStorageFailure("OSS HEAD failed", exception);
        }
    }

    @Override
    public byte[] downloadObjectBytesBounded(String objectKey, long maximumBytes) {
        try {
            return ossUtils.downloadObjectBytesBounded(
                    bucket,
                    region,
                    endpoint,
                    objectKey,
                    maximumBytes);
        } catch (RuntimeException exception) {
            throw mapStorageFailure("OSS bounded download failed", exception);
        }
    }

    @Override
    public String copyObjectToPublic(
            String sourceObjectKey,
            String destinationObjectKey,
            String contentType) {
        try {
            ossUtils.copyObjectToBucket(
                    bucket,
                    sourceObjectKey,
                    bucket,
                    destinationObjectKey,
                    region,
                    endpoint,
                    contentType);
            return publicBaseUrl + "/" + destinationObjectKey;
        } catch (RuntimeException exception) {
            throw mapStorageFailure("OSS copy failed", exception);
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        try {
            ossUtils.deleteObject(bucket, region, endpoint, objectKey);
        } catch (RuntimeException exception) {
            throw mapStorageFailure("OSS delete failed", exception);
        }
    }

    private static RuntimeException mapStorageFailure(
            String message,
            RuntimeException exception) {
        Throwable cause = rootCause(exception);
        if (cause instanceof ServiceException serviceException
                && serviceException.statusCode() == 404) {
            return new ObjectNotFoundException(exception);
        }
        if (cause instanceof AliyunOssUtils.ObjectTooLargeException) {
            return new ObjectTooLargeException(exception);
        }
        return new StorageException(message, exception);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String requireHttpsUrl(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.startsWith("https://")) {
            throw new IllegalArgumentException(name + " must use HTTPS");
        }
        return normalized;
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
