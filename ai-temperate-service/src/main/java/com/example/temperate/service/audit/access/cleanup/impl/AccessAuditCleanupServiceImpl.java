package com.example.temperate.service.audit.access.cleanup.impl;

import com.example.temperate.service.audit.access.cleanup.AccessAuditCleanupBatchService;
import com.example.temperate.service.audit.access.cleanup.AccessAuditCleanupService;
import com.example.temperate.service.audit.access.config.AccessAuditProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 每日编排访问审计过期清理，失败时记录低基数指标并等待下一轮而不进行无限重试。
 */
@Service
@ConditionalOnProperty(prefix = "app.access-audit", name = "enabled", havingValue = "true")
public final class AccessAuditCleanupServiceImpl implements AccessAuditCleanupService {

    private final AccessAuditCleanupBatchService batchService;
    private final AccessAuditProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public AccessAuditCleanupServiceImpl(
            AccessAuditCleanupBatchService batchService,
            AccessAuditProperties properties,
            MeterRegistry meterRegistry) {
        this(batchService, properties, meterRegistry, Clock.systemUTC());
    }

    AccessAuditCleanupServiceImpl(
            AccessAuditCleanupBatchService batchService,
            AccessAuditProperties properties,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.batchService = batchService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    @Scheduled(cron = "0 30 3 * * *")
    public void cleanupExpired() {
        Instant cutoff = clock.instant().minus(properties.retention());
        try {
            for (int batch = 0; batch < properties.cleanupMaxBatches(); batch++) {
                int deleted = batchService.deleteBatch(cutoff, properties.cleanupBatchSize());
                if (deleted < properties.cleanupBatchSize()) {
                    break;
                }
            }
            counter("completed");
        } catch (RuntimeException exception) {
            // 清理失败保留数据比无界重试更安全，下一次调度会从相同时间边界继续有序删除。
            counter("error");
        }
    }

    private void counter(String outcome) {
        meterRegistry.counter("auth.access_audit.cleanup", "outcome", outcome).increment();
    }
}
