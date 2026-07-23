package com.example.temperate.service.audit.access.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 验证访问审计批量、保留期和数据库截止时间配置在启动绑定阶段拒绝无界或无效值。
 */
class AccessAuditPropertiesTest {

    @Test
    void rejectsDatabaseBatchesAboveTheProjectBoundary() {
        assertThatThrownBy(() -> new AccessAuditProperties(
                true,
                "test",
                Duration.ofDays(30),
                501,
                Duration.ofMillis(500),
                15,
                1000,
                50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAZeroRetentionPeriod() {
        assertThatThrownBy(() -> new AccessAuditProperties(
                true,
                "test",
                Duration.ZERO,
                200,
                Duration.ofMillis(500),
                15,
                1000,
                50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAZeroDatabaseStoreTimeout() {
        assertThatThrownBy(() -> new AccessAuditProperties(
                true,
                "test",
                Duration.ofDays(30),
                200,
                Duration.ofMillis(500),
                0,
                1000,
                50))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
