package com.example.temperate.service.audit.access.cleanup;

import java.time.Instant;

/**
 * 定义访问审计过期数据的单批事务删除边界，供调度器按配置次数重复调用。
 */
public interface AccessAuditCleanupBatchService {

    int deleteBatch(Instant cutoff, int limit);
}
