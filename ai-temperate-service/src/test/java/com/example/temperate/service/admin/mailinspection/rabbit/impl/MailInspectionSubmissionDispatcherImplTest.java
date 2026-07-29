package com.example.temperate.service.admin.mailinspection.rabbit.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 锁定 Submission v2 必须先完成 Work Confirm 和 Marker Confirm，再登记 Redis 派发状态。
 */
final class MailInspectionSubmissionDispatcherImplTest {

    @Test
    void writesDispatchStateAfterConfirmedRabbitMessages() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/admin/"
                        + "mailinspection/rabbit/impl/"
                        + "MailInspectionSubmissionDispatcherImpl.java"));

        int work = source.indexOf("publishWork(message, credentials)");
        int marker = source.indexOf("markerPublisher.publish(", work);
        int redis = source.indexOf(
                "jobStore.recordSubmissionDispatched(", marker);
        assertThat(work).isGreaterThanOrEqualTo(0);
        assertThat(marker).isGreaterThan(work);
        assertThat(redis).isGreaterThan(marker);
        assertThat(source)
                .contains(
                        "MailInspectionRabbitNames.SUBMISSION_SCHEMA_VERSION",
                        "message.jobKeyHash()",
                        "pauseOnRedisFailure",
                        "submissionListenerControl.stop(type)",
                        "workListenerControl.stop(type)")
                .doesNotContain(
                        "jobInternalId",
                        "MailInspectionJobState",
                        "InMemoryAdminMailInspectionJobStore");
    }
}
