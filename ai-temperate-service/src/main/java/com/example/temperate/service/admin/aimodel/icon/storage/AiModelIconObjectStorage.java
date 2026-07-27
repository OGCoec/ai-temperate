package com.example.temperate.service.admin.aimodel.icon.storage;

/**
 * 定义模型图标业务使用的最小 OSS 存储端口，隔离阿里云 SDK 和部署配置。
 */
public interface AiModelIconObjectStorage {

    String putObject(
            String objectKey,
            byte[] bytes,
            String contentType,
            boolean forbidOverwrite);

    void deleteObject(String objectKey);
}
