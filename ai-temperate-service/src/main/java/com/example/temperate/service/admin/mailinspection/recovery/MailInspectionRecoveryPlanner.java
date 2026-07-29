package com.example.temperate.service.admin.mailinspection.recovery;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionRabbitNames;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionChunkMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionWorkMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 按稳定任务身份对恢复消息分组，使同一物理 Queue 可以安全容纳多个历史终态任务。
 */
@Component
public final class MailInspectionRecoveryPlanner {

    public Map<JobKey, List<ScannedMessage>> groupByJob(
            List<ScannedMessage> messages) {
        Map<JobKey, List<ScannedMessage>> groups = new LinkedHashMap<>();
        for (ScannedMessage message : messages) {
            groups.computeIfAbsent(
                            keyOf(message),
                            ignored -> new ArrayList<>())
                    .add(message);
        }
        Map<JobKey, List<ScannedMessage>> immutable =
                new LinkedHashMap<>();
        groups.forEach((key, value) ->
                immutable.put(key, List.copyOf(value)));
        return Map.copyOf(immutable);
    }

    private static JobKey keyOf(ScannedMessage message) {
        return switch (message.body()) {
            case MailInspectionSubmissionChunkMessage value ->
                    JobKey.v2(
                            value.jobInternalId(),
                            value.jobId(),
                            value.inspectionType(),
                            value.clientRequestId(),
                            value.requestFingerprint(),
                            value.createdAt());
            case MailInspectionDispatchMarkerMessage value ->
                    JobKey.v2(
                            value.jobInternalId(),
                            value.jobId(),
                            value.inspectionType(),
                            value.clientRequestId(),
                            value.requestFingerprint(),
                            value.createdAt());
            case MailInspectionWorkMessage value -> value.schemaVersion()
                    == MailInspectionRabbitNames.LEGACY_WORK_SCHEMA_VERSION
                            ? JobKey.legacy(
                                    value.jobInternalId(),
                                    value.jobId(),
                                    value.inspectionType())
                            : JobKey.v2(
                                    value.jobInternalId(),
                                    value.jobId(),
                                    value.inspectionType(),
                                    value.clientRequestId(),
                                    value.requestFingerprint(),
                                    value.createdAt());
            default -> throw new IllegalStateException(
                    "unsupported mail inspection recovery message");
        };
    }

    public enum QueueKind {
        SUBMISSION,
        MARKER,
        WORK
    }

    /**
     * deliveryTag 在反序列化前由恢复会话登记，失败结算时不会漏掉刚读出的消息。
     */
    public record ScannedMessage(
            long deliveryTag,
            QueueKind kind,
            Object body) {
    }

    public record JobKey(
            long internalId,
            String jobId,
            MailInspectionType type,
            String clientRequestId,
            String requestFingerprint,
            Instant createdAt,
            boolean legacy) {

        static JobKey v2(
                long internalId,
                String jobId,
                MailInspectionType type,
                String clientRequestId,
                String requestFingerprint,
                Instant createdAt) {
            return new JobKey(
                    internalId,
                    jobId,
                    type,
                    clientRequestId,
                    requestFingerprint,
                    createdAt,
                    false);
        }

        static JobKey legacy(
                long internalId,
                String jobId,
                MailInspectionType type) {
            return new JobKey(
                    internalId,
                    jobId,
                    type,
                    null,
                    null,
                    null,
                    true);
        }
    }
}
