package com.example.temperate.service.admin.aimodel.icon.persistence;

import com.example.temperate.model.ai.entity.AiModelIcon;

/**
 * 定义模型图标数据库短事务边界，不在事务中执行 HTTP 或 OSS I/O。
 */
public interface AdminAiModelIconPersistenceService {

    AiModelIcon create(AiModelIcon icon);

    AiModelIcon update(
            long id,
            String iconName,
            String iconUrl,
            String objectKey,
            String description);

    AiModelIcon delete(long id);
}
