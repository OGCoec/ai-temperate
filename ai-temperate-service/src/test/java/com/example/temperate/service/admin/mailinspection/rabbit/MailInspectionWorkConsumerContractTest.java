package com.example.temperate.service.admin.mailinspection.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 锁定 Rabbit v2 消费必须先校验 Redis 权威文档，并在 Redis 结果落盘后才完成监听 Mono。
 */
final class MailInspectionWorkConsumerContractTest {

    @Test
    void completesOnlyAfterAuthoritativeResultWrite() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/admin/"
                        + "mailinspection/rabbit/"
                        + "MailInspectionWorkConsumer.java"));

        int metaRead = source.indexOf("jobStore.findSnapshotMeta(");
        int claim = source.indexOf("jobStore.claimLine(");
        int resultWrite = source.indexOf("jobStore.recordResult(");
        int completion = source.indexOf(".then();", resultWrite);
        assertThat(metaRead).isGreaterThanOrEqualTo(0);
        assertThat(claim).isGreaterThan(metaRead);
        assertThat(resultWrite).isGreaterThan(claim);
        assertThat(completion).isGreaterThan(resultWrite);
        assertThat(source)
                .contains(
                        "MailInspectionRabbitNames.WORK_SCHEMA_VERSION",
                        "message.jobKeyHash()",
                        "keyHasher.hashJobId(message.jobId())",
                        "pauseOnRedisFailure",
                        "listenerControl.stop(type)")
                .doesNotContain(
                        "LEGACY_WORK_SCHEMA_VERSION",
                        "jobInternalId",
                        "InMemoryAdminMailInspectionJobStore");
    }
}
