package com.example.temperate.service.audit.access.cleanup;

/**
 * 编排访问审计 30 天保留期清理，并强制每轮执行的批次数和单批删除量有界。
 */
public interface AccessAuditCleanupService {

    void cleanupExpired();
}
