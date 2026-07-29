package com.example.temperate.service.admin.mailinspection.recovery;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionDispatchMarkerMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionSubmissionChunkMessage;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionWorkMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 按 Rabbit v2 的 Job HMAC 对恢复诊断消息分组，不接受旧 Long ID 或 v1 消息。
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
                            value.jobKeyHash(),
                            value.jobId(),
                            value.inspectionType(),
                            value.clientRequestId(),
                            value.requestFingerprint(),
                            value.createdAt());
            case MailInspectionDispatchMarkerMessage value ->
                    JobKey.v2(
                            value.jobKeyHash(),
                            value.jobId(),
                            value.inspectionType(),
                            value.clientRequestId(),
                            value.requestFingerprint(),
                            value.createdAt());
            case MailInspectionWorkMessage value ->
                    JobKey.v2(
                            value.jobKeyHash(),
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
     * 保存 Rabbit 物理投递标签和固定消息类型，供安全结算流程显式 ACK 或 NACK。
     */
    public record ScannedMessage(
            long deliveryTag,
            QueueKind kind,
            Object body) {
    }

    /**
     * 表示 Rabbit v2 消息的稳定任务身份，jobHash 是分组主键且不暴露公开 Job ID 到日志。
     */
    public record JobKey(
            String jobHash,
            String jobId,
            MailInspectionType type,
            String clientRequestId,
            String requestFingerprint,
            Instant createdAt) {

        static JobKey v2(
                String jobHash,
                String jobId,
                MailInspectionType type,
                String clientRequestId,
                String requestFingerprint,
                Instant createdAt) {
            return new JobKey(
                    jobHash,
                    jobId,
                    type,
                    clientRequestId,
                    requestFingerprint,
                    createdAt);
        }
    }
}
