package com.example.temperate.service.admin.aimodel.cache;

import java.util.List;

/**
 * 表示 Redis 中全部启用 AI 模型的版本化聚合快照明文结构。
 *
 * <p>该对象只在应用内存中短暂存在，写入 Redis 前必须整体通过 AES-256-GCM 加密。</p>
 */
public record AiModelCacheSnapshot(int schemaVersion, List<AiModelCacheEntry> models) {

    // v3 增加缓存输入倍率，避免计费链路把缺少该字段的旧快照当作完整模型配置。
    public static final int CURRENT_SCHEMA_VERSION = 3;

    public AiModelCacheSnapshot {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
