package com.example.temperate.service.admin.aimodel.cache;

import java.util.List;

/**
 * 表示 Redis 中全部启用 AI 模型的版本化聚合快照明文结构。
 *
 * <p>该对象只在应用内存中短暂存在，写入 Redis 前必须整体通过 AES-256-GCM 加密。</p>
 */
public record AiModelCacheSnapshot(int schemaVersion, List<AiModelCacheEntry> models) {

    // v7 将模型创建日期纳入快照，供公开 Models API 输出稳定的 OpenAI created 字段；旧快照必须回源重建。
    public static final int CURRENT_SCHEMA_VERSION = 7;

    public AiModelCacheSnapshot {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
