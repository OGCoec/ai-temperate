package com.example.temperate.service.admin.aimodel.cache;

import java.util.Optional;

/**
 * 定义启用 AI 模型加密聚合快照的读取、数据库回源与事务后刷新边界。
 *
 * <p>普通读取可以在缓存缺失时一次批量回源；写业务只触发一次逻辑刷新，不得按模型逐条访问 Redis，
 * 数据库始终是事实来源。</p>
 */
public interface AiModelCacheService {

    Optional<AiModelCacheSnapshot> findEnabledSnapshot();

    AiModelCacheSnapshot getOrLoadEnabledSnapshot();

    void refreshEnabledSnapshot();
}
