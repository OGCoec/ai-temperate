package com.example.temperate.service.admin.mailinspection.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionPendingItem;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证重启后由 RabbitMQ 重建的任务只能进入等待管理员批准状态，并明确标记旧结果历史已经丢失。
 */
final class MailInspectionRecoveredJobStateTest {

    @Test
    void recoveredStateCannotRunBeforeExplicitResume() {
        Instant recoveredAt = Instant.parse("2026-07-28T12:00:00Z");
        MailInspectionJobState state = MailInspectionJobState.recovered(
                1L,
                "AAAAAAAAAAE",
                MailInspectionType.OPENAI_STATUS,
                1_000,
                1_000,
                0,
                0,
                32,
                Instant.parse("2026-07-28T11:00:00Z"),
                recoveredAt,
                List.of(
                        new MailInspectionPendingItem(65, "o***@example.test"),
                        new MailInspectionPendingItem(1_000, "a***@example.test")));

        var snapshot = state.snapshot();

        assertThat(snapshot.status())
                .isEqualTo(MailInspectionJobStatus.AWAITING_ADMIN_RESUME);
        assertThat(snapshot.resumeRequired()).isTrue();
        assertThat(snapshot.recoveredAfterRestart()).isTrue();
        assertThat(snapshot.resultHistoryLost()).isTrue();
        assertThat(snapshot.lostResultCount()).isEqualTo(998);
        assertThat(snapshot.remainingCount()).isEqualTo(2);
        assertThat(snapshot.businessConcurrency()).isEqualTo(32);
        assertThat(snapshot.pendingItems()).extracting(MailInspectionPendingItem::lineNumber)
                .containsExactly(65, 1_000);
    }
}
