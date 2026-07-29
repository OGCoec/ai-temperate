package com.example.temperate.service.admin.mailinspection.recovery.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryPlanner.QueueKind;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryPlanner.ScannedMessage;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryPlanner;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证恢复分组只按 Rabbit v2 完整 Job HMAC 隔离，不再使用可碰撞的 Long 内部 ID。
 */
final class MailInspectionRecoveryIsolationIntegrationTest {

    @Test
    void isolatesMessagesByFullJobHash() {
        Instant now = Instant.parse("2026-07-29T10:00:00Z");
        MailInspectionDispatchMarkerMessage first =
                marker("AZ9nEjRWeJCrze8SNFZ4kA", "A".repeat(43), now);
        MailInspectionDispatchMarkerMessage second =
                marker("BZ9nEjRWeJCrze8SNFZ4kA", "B".repeat(43), now);

        var groups = new MailInspectionRecoveryPlanner().groupByJob(List.of(
                new ScannedMessage(1L, QueueKind.MARKER, first),
                new ScannedMessage(2L, QueueKind.MARKER, second)));

        assertThat(groups).hasSize(2);
        assertThat(groups.keySet())
                .extracting(key -> key.jobHash())
                .containsExactlyInAnyOrder(
                        "A".repeat(43),
                        "B".repeat(43));
    }

    private static MailInspectionDispatchMarkerMessage marker(
            String jobId,
            String jobHash,
            Instant now) {
        return new MailInspectionDispatchMarkerMessage(
                "message-id",
                MailInspectionRabbitNames.DISPATCH_MARKER_EVENT_TYPE,
                MailInspectionRabbitNames.DISPATCH_MARKER_SCHEMA_VERSION,
                now,
                "trace-test",
                "550e8400-e29b-41d4-a716-446655440000",
                "C".repeat(43),
                jobId,
                jobHash,
                MailInspectionType.OPENAI_STATUS,
                0,
                1,
                4,
                now,
                now);
    }
}
