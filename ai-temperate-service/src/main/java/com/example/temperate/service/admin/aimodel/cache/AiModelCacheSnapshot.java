package com.example.temperate.service.admin.aimodel.cache;

import java.util.List;

/**
 * 表示 Redis 中全部启用 AI 模型的版本化聚合快照明文结构。
 *
 * <p>该对象只在应用内存中短暂存在，写入 Redis 前必须整体通过 AES-256-GCM 加密。</p>
 */
public record AiModelCacheSnapshot(int schemaVersion, List<AiModelCacheEntry> models) {

    // v2 将图片、视频能力收敛为能力大类并新增音频大类，避免新应用误读旧枚举快照。
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public AiModelCacheSnapshot {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
