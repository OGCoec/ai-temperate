package com.example.temperate.service.admin.aimodel.cache;

import java.util.List;

/**
 * 表示 Redis 中全部启用 AI 模型的版本化聚合快照明文结构。
 *
 * <p>该对象只在应用内存中短暂存在，写入 Redis 前必须整体通过 AES-256-GCM 加密。</p>
 */
public record AiModelCacheSnapshot(int schemaVersion, List<AiModelCacheEntry> models) {

    // v5 将 K 的换算语义从 1024 Token 切换为官方十进制 1000 Token，旧快照不得继续参与容量和计费链路。
    public static final int CURRENT_SCHEMA_VERSION = 5;

    public AiModelCacheSnapshot {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
