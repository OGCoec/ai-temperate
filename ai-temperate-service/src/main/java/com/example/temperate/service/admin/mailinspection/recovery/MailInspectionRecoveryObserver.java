package com.example.temperate.service.admin.mailinspection.recovery;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.time.Duration;

/**
 * 观察邮箱检查恢复与 Marker 回收结果，为实现层提供低基数、无敏感字段的指标边界。
 */
public interface MailInspectionRecoveryObserver {

    void recoveryCompleted(
            MailInspectionType type,
            boolean successful,
            Duration elapsed);

    void markersCleaned(
            MailInspectionType type,
            int count);

    void markerQueueObserved(
            MailInspectionType type,
            int ready,
            int unacked);

    void nackRequeueFailed(MailInspectionType type);
}
