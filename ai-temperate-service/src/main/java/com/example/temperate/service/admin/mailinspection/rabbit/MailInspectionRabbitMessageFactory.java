package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 将已校验邮箱凭证转换为持久 Rabbit 工作消息，并确保明文凭证只进入独立加密载荷。
 */
@Component
public final class MailInspectionRabbitMessageFactory {

    private final AdminMailInspectionPayloadProtector payloadProtector;
    private final Clock clock;

    public MailInspectionRabbitMessageFactory(
            AdminMailInspectionPayloadProtector payloadProtector,
            Clock clock) {
        this.payloadProtector = Objects.requireNonNull(payloadProtector);
        this.clock = Objects.requireNonNull(clock);
    }

    public MailInspectionWorkMessage create(
            long jobInternalId,
            String jobId,
            MailInspectionType inspectionType,
            int requestedCount,
            int acceptedCount,
            int duplicateCount,
            int invalidCount,
            int businessConcurrency,
            Instant createdAt,
            MailboxCredential credential) {
        String messageId = UUID.randomUUID().toString();
        String traceId = safeTraceId(messageId);
        return new MailInspectionWorkMessage(
                messageId,
                MailInspectionRabbitNames.EVENT_TYPE,
                MailInspectionRabbitNames.LEGACY_WORK_SCHEMA_VERSION,
                clock.instant(),
                traceId,
                jobInternalId,
                jobId,
                inspectionType,
                credential.lineNumber(),
                requestedCount,
                acceptedCount,
                duplicateCount,
                invalidCount,
                businessConcurrency,
                createdAt,
                payloadProtector.protect(
                        messageId,
                        jobId,
                        inspectionType,
                        credential));
    }

    public MailInspectionWorkMessage createFromSubmission(
            MailInspectionSubmissionChunkMessage submission,
            MailInspectionSubmissionCredential credential) {
        String messageId = UUID.nameUUIDFromBytes((submission.jobId()
                        + "\u0000"
                        + credential.lineNumber())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        MailboxCredential mailboxCredential = new MailboxCredential(
                credential.lineNumber(),
                credential.email(),
                credential.clientId(),
                credential.refreshToken());
        return new MailInspectionWorkMessage(
                messageId,
                MailInspectionRabbitNames.EVENT_TYPE,
                MailInspectionRabbitNames.WORK_SCHEMA_VERSION,
                clock.instant(),
                submission.traceId(),
                submission.jobInternalId(),
                submission.jobId(),
                submission.inspectionType(),
                credential.lineNumber(),
                submission.requestedCount(),
                submission.acceptedCount(),
                submission.duplicateCount(),
                submission.invalidCount(),
                submission.businessConcurrency(),
                submission.createdAt(),
                payloadProtector.protect(
                        messageId,
                        submission.jobId(),
                        submission.inspectionType(),
                        mailboxCredential),
                submission.clientRequestId(),
                submission.requestFingerprint(),
                submission.chunkIndex());
    }

    private static String safeTraceId(String fallback) {
        String traceId = MDC.get("traceId");
        if (traceId == null
                || traceId.isBlank()
                || traceId.length() > 128) {
            return fallback;
        }
        return traceId;
    }
}
