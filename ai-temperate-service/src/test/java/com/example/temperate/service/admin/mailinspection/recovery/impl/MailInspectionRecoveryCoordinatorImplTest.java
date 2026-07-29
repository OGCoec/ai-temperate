package com.example.temperate.service.admin.mailinspection.recovery.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 锁定启动恢复以 Redis 为唯一权威来源，Rabbit 有孤儿消息时保持类型不可用。
 */
final class MailInspectionRecoveryCoordinatorImplTest {

    @Test
    void neverRebuildsJobStateFromRabbitPayloads() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/admin/"
                        + "mailinspection/recovery/impl/"
                        + "MailInspectionRecoveryCoordinatorImpl.java"));

        assertThat(source).contains(
                "jobStore.findActiveByType(type)",
                "active.isEmpty() && ready > 0",
                "Rabbit contains mail work without Redis authority",
                "jobStore.markUnavailable(");
        assertThat(source).doesNotContain(
                "basicGet(",
                "restorePendingJob(",
                "MailInspectionJobState",
                "jobInternalId",
                "LEGACY_WORK_SCHEMA_VERSION");
    }
}
