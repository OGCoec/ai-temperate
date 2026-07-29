package com.example.temperate.service.admin.mailinspection.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证任务状态并发计数、结果行号排序、汇总和完成保留时间。
 */
final class MailInspectionJobStateTest {

    @Test
    void snapshotsResultsInInputOrderAndCountsProcessedItems() {
        Instant now = Instant.parse("2026-07-28T10:00:00Z");
        MailInspectionJobState state = new MailInspectionJobState(
                7L,
                "AAAAAAAAAAc",
                MailInspectionType.OPENAI_STATUS,
                2,
                2,
                0,
                0,
                now,
                List.of());
        state.markRunning(now);
        state.itemStarted(2);
        state.recordResult(result(2));
        state.itemStarted(1);
        state.recordResult(result(1));
        state.complete(now.plusSeconds(1), Duration.ofMinutes(30));

        var snapshot = state.snapshot();

        assertThat(snapshot.processedCount()).isEqualTo(2);
        assertThat(snapshot.results())
                .extracting(MailInspectionResult::lineNumber)
                .containsExactly(1, 2);
        assertThat(snapshot.expiresAt()).isEqualTo(now.plusSeconds(1801));
    }

    @Test
    void expiredCleanupClaimPreventsConcurrentClientResubmission() {
        Instant now = Instant.parse("2026-07-28T10:00:00Z");
        MailInspectionJobState state = MailInspectionJobState.submitting(
                9L,
                "AAAAAAAAAAk",
                MailInspectionType.OPENAI_STATUS,
                1,
                1,
                0,
                0,
                4,
                "550e8400-e29b-41d4-a716-446655440000",
                new MailInspectionRequestFingerprint("A".repeat(43)),
                1,
                now,
                Duration.ofHours(6),
                List.of());

        assertThat(state.tryClaimIncompleteCleanup(
                now.plus(Duration.ofHours(6)))).isTrue();
        assertThat(state.markDispatching(
                now.plus(Duration.ofHours(6)),
                Duration.ofHours(6))).isFalse();
    }

    private static MailInspectionResult result(int lineNumber) {
        return MailInspectionResult.inputFailure(
                lineNumber,
                null,
                MailInspectionResultStatus.INVALID_CREDENTIAL_FORMAT,
                "invalid");
    }
}
