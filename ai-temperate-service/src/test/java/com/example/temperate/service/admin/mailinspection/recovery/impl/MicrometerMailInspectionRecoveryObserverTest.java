package com.example.temperate.service.admin.mailinspection.recovery.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.AdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.MailInspectionAcceptanceState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 验证恢复指标只使用检查类型和固定结果标签，并能反映类型闸门与 Marker 观测值。
 */
final class MicrometerMailInspectionRecoveryObserverTest {

    @Test
    void recordsPerTypeAcceptanceRecoveryAndMarkerMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdminMailInspectionJobStore jobStore =
                mock(AdminMailInspectionJobStore.class);
        when(jobStore.acceptanceState(any(MailInspectionType.class)))
                .thenReturn(MailInspectionAcceptanceState.RECOVERING);
        when(jobStore.acceptanceState(MailInspectionType.OPENAI_STATUS))
                .thenReturn(MailInspectionAcceptanceState.ACCEPTING);
        MicrometerMailInspectionRecoveryObserver observer =
                new MicrometerMailInspectionRecoveryObserver(
                        registry,
                        jobStore);

        observer.recoveryCompleted(
                MailInspectionType.OPENAI_STATUS,
                true,
                Duration.ofMillis(25));
        observer.markersCleaned(
                MailInspectionType.OPENAI_STATUS,
                2);
        observer.markerQueueObserved(
                MailInspectionType.OPENAI_STATUS,
                3,
                1);
        observer.nackRequeueFailed(
                MailInspectionType.OPENAI_STATUS);

        assertThat(registry.get(
                        "admin_mail_inspection_acceptance_state")
                .tag(
                        "inspectionType",
                        MailInspectionType.OPENAI_STATUS.name())
                .gauge()
                .value()).isEqualTo(1D);
        assertThat(registry.get(
                        "admin_mail_inspection_recovery_total")
                .tag(
                        "inspectionType",
                        MailInspectionType.OPENAI_STATUS.name())
                .tag("outcome", "success")
                .counter()
                .count()).isEqualTo(1D);
        assertThat(registry.get(
                        "admin_mail_inspection_terminal_markers_cleaned_total")
                .tag(
                        "inspectionType",
                        MailInspectionType.OPENAI_STATUS.name())
                .counter()
                .count()).isEqualTo(2D);
        assertThat(registry.get(
                        "admin_mail_inspection_marker_ready")
                .tag(
                        "inspectionType",
                        MailInspectionType.OPENAI_STATUS.name())
                .gauge()
                .value()).isEqualTo(3D);
        assertThat(registry.get(
                        "admin_mail_inspection_marker_unacked")
                .tag(
                        "inspectionType",
                        MailInspectionType.OPENAI_STATUS.name())
                .gauge()
                .value()).isEqualTo(1D);
        assertThat(registry.get(
                        "admin_mail_inspection_nack_requeue_failure_total")
                .tag(
                        "inspectionType",
                        MailInspectionType.OPENAI_STATUS.name())
                .counter()
                .count()).isEqualTo(1D);
    }
}
