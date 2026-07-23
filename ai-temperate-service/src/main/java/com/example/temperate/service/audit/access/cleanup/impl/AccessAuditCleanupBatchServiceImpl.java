package com.example.temperate.service.audit.access.cleanup.impl;

import com.example.temperate.mapper.audit.access.AccessRequestAuditMapper;
import com.example.temperate.service.audit.access.cleanup.AccessAuditCleanupBatchService;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在独立本地事务中按稳定时间顺序删除一批过期访问审计记录，避免长事务锁住无界数据。
 */
@Service
@ConditionalOnProperty(prefix = "app.access-audit", name = "enabled", havingValue = "true")
public final class AccessAuditCleanupBatchServiceImpl implements AccessAuditCleanupBatchService {

    private final AccessRequestAuditMapper mapper;

    public AccessAuditCleanupBatchServiceImpl(AccessRequestAuditMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public int deleteBatch(Instant cutoff, int limit) {
        return mapper.deleteExpiredBatch(cutoff, limit);
    }
}
