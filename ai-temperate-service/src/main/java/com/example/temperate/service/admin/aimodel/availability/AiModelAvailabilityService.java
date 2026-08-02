package com.example.temperate.service.admin.aimodel.availability;

import java.util.List;
import java.util.Set;

/**
 * 定义模型实际调用前必须执行的数据库启用状态确认边界。
 *
 * <p>Redis 快照只用于发现候选模型，不得替代该强制数据库检查。</p>
 */
public interface AiModelAvailabilityService {

    boolean isEnabled(long internalModelId);

    Set<Long> findEnabledIds(List<Long> internalModelIds);
}
