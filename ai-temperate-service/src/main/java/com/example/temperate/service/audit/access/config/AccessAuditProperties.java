package com.example.temperate.service.audit.access.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定访问请求审计的启用开关、独立 HMAC 密钥、保留期、数据库截止时间以及批量处理上限。
 */
@ConfigurationProperties(prefix = "app.access-audit")
public record AccessAuditProperties(
        boolean enabled,
        String hmacSecretBase64,
        Duration retention,
        int batchSize,
        Duration batchTimeout,
        int storeTimeoutSeconds,
        int cleanupBatchSize,
        int cleanupMaxBatches) {

    public AccessAuditProperties {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Access audit retention must be positive.");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Access audit batch size must be between 1 and 500.");
        }
        if (batchTimeout == null || batchTimeout.isNegative() || batchTimeout.isZero()) {
            throw new IllegalArgumentException("Access audit batch timeout must be positive.");
        }
        if (storeTimeoutSeconds < 1 || storeTimeoutSeconds > 120) {
            throw new IllegalArgumentException(
                    "Access audit store timeout must be between 1 and 120 seconds.");
        }
        if (cleanupBatchSize < 1 || cleanupBatchSize > 2000) {
            throw new IllegalArgumentException(
                    "Access audit cleanup batch size must be between 1 and 2000.");
        }
        if (cleanupMaxBatches < 1 || cleanupMaxBatches > 100) {
            throw new IllegalArgumentException(
                    "Access audit cleanup max batches must be between 1 and 100.");
        }
    }
}
