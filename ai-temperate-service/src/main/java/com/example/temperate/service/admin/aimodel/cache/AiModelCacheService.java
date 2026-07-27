package com.example.temperate.service.admin.aimodel.cache;

import java.util.Optional;

/**
 * 定义启用 AI 模型加密聚合快照的读取与事务后刷新边界。
 *
 * <p>调用方只触发一次逻辑刷新，不得按模型逐条访问 Redis；数据库始终是事实来源。</p>
 */
public interface AiModelCacheService {

    Optional<AiModelCacheSnapshot> findEnabledSnapshot();

    void refreshEnabledSnapshot();
}
