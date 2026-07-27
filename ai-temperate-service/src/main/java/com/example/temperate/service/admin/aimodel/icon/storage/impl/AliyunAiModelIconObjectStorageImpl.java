package com.example.temperate.service.admin.aimodel.icon.storage.impl;

import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.example.temperate.common.aliyun.AliyunOssUtils;
import com.example.temperate.service.admin.aimodel.icon.storage.AiModelIconObjectStorage;
import com.example.temperate.service.admin.aimodel.icon.storage.AiModelIconStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 使用项目通用阿里云 OSS 工具实现模型图标专用对象存储端口。
 *
 * <p>该实现固定公共读与重新验证缓存策略，业务层负责 Object Key、图片内容和补偿删除。</p>
 */
@Service
public final class AliyunAiModelIconObjectStorageImpl implements AiModelIconObjectStorage {

    private static final String CACHE_CONTROL = "public, max-age=0, must-revalidate";

    private final AliyunOssUtils ossUtils;
    private final String bucket;
    private final String region;
    private final String endpoint;
    private final String publicBaseUrl;

    public AliyunAiModelIconObjectStorageImpl(
            AliyunOssUtils ossUtils,
            @Value("${ai-model-icon.oss.bucket}") String bucket,
            @Value("${ai-model-icon.oss.region}") String region,
            @Value("${ai-model-icon.oss.endpoint}") String endpoint,
            @Value("${ai-model-icon.oss.public-base-url}") String publicBaseUrl) {
        this.ossUtils = ossUtils;
        this.bucket = requireText(bucket, "bucket");
        this.region = requireText(region, "region");
        this.endpoint = requireHttps(endpoint, "endpoint");
        this.publicBaseUrl = stripTrailingSlash(requireHttps(publicBaseUrl, "publicBaseUrl"));
    }

    @Override
    public String putObject(
            String objectKey,
            byte[] bytes,
            String contentType,
            boolean forbidOverwrite) {
        try {
            ossUtils.putObjectBytes(
                    bucket,
                    region,
                    endpoint,
                    objectKey,
                    bytes,
                    contentType,
                    CACHE_CONTROL,
                    forbidOverwrite);
            return publicBaseUrl + "/" + objectKey;
        } catch (RuntimeException exception) {
            throw mapFailure("AI model icon OSS upload failed", exception);
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        try {
            ossUtils.deleteObject(bucket, region, endpoint, objectKey);
        } catch (RuntimeException exception) {
            throw mapFailure("AI model icon OSS delete failed", exception);
        }
    }

    private static AiModelIconStorageException mapFailure(
            String message,
            RuntimeException exception) {
        Throwable cause = rootCause(exception);
        boolean conflict = cause instanceof ServiceException serviceException
                && serviceException.statusCode() == 409;
        return new AiModelIconStorageException(message, conflict, exception);
    }

    private static Throwable rootCause(Throwable value) {
        Throwable current = value;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String requireHttps(String value, String name) {
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
