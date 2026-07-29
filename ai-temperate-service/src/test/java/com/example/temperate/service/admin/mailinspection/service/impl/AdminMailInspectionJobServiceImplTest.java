package com.example.temperate.service.admin.mailinspection.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 锁定任务创建只使用 Hybrid ID、Redis 原子预留与一万行边界，不再构造内存可变任务。
 */
final class AdminMailInspectionJobServiceImplTest {

    @Test
    void createsHybridRedisJobWithoutLegacyLongIdentity() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/admin/"
                        + "mailinspection/service/impl/"
                        + "AdminMailInspectionJobServiceImpl.java"));

        assertThat(source)
                .contains(
                        "jobIdCodec.encode(hybridIdWorker.nextId())",
                        "properties.job().maxCredentialLines()",
                        "jobStore.reserveOrFind(",
                        "keyHasher.hashJobId(candidateJobId)");
        assertThat(source)
                .doesNotContain(
                        "SnowflakeIdWorker",
                        "jobInternalId",
                        "MailInspectionJobState",
                        "InMemoryAdminMailInspectionJobStore",
                        "pollAfterMillis");
    }
}
