package com.example.temperate.service.admin.aimodel.cache;

import java.util.List;

/**
 * 表示 Redis 中全部启用 AI 模型的版本化聚合快照明文结构。
 *
 * <p>该对象只在应用内存中短暂存在，写入 Redis 前必须整体通过 AES-256-GCM 加密。</p>
 */
public record AiModelCacheSnapshot(int schemaVersion, List<AiModelCacheEntry> models) {

    // v6 使用细分后的媒体能力枚举，旧快照中的聚合能力值不得进入模型选择和附件授权链路。
    public static final int CURRENT_SCHEMA_VERSION = 6;

    public AiModelCacheSnapshot {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
