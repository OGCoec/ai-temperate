package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 将解析后的有效凭证转换为稳定编号的加密Submission Chunk和无敏感内容的派发Marker。
 */
@Component
public final class MailInspectionSubmissionMessageFactory {

    private final MailInspectionSubmissionChunker chunker;
    private final AdminMailInspectionSubmissionPayloadProtector protector;
    private final Clock clock;

    public MailInspectionSubmissionMessageFactory(
            MailInspectionSubmissionChunker chunker,
            AdminMailInspectionSubmissionPayloadProtector protector,
            Clock clock) {
        this.chunker = Objects.requireNonNull(chunker);
        this.protector = Objects.requireNonNull(protector);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<MailInspectionSubmissionChunkMessage> createChunks(
            String clientRequestId,
            MailInspectionRequestFingerprint fingerprint,
            String jobId,
            String jobKeyHash,
            MailInspectionType type,
            int requestedCount,
            int acceptedCount,
            int duplicateCount,
            int invalidCount,
            int businessConcurrency,
            Instant createdAt,
            List<MailboxCredential> credentials) {
        List<List<MailboxCredential>> chunks = chunker.chunk(credentials);
        List<MailInspectionSubmissionChunkMessage> messages =
                new ArrayList<>(chunks.size());
        String traceId = safeTraceId(jobId);
        for (int index = 0; index < chunks.size(); index++) {
            String messageId = stableId(clientRequestId, index, "submission");
            messages.add(new MailInspectionSubmissionChunkMessage(
                    messageId,
                    MailInspectionRabbitNames.SUBMISSION_EVENT_TYPE,
                    MailInspectionRabbitNames.SUBMISSION_SCHEMA_VERSION,
                    clock.instant(),
                    traceId,
                    clientRequestId,
                    fingerprint.value(),
                    jobId,
                    jobKeyHash,
                    type,
                    index,
                    chunks.size(),
                    requestedCount,
                    acceptedCount,
                    duplicateCount,
                    invalidCount,
                    businessConcurrency,
                    createdAt,
                    protector.protect(
                            messageId,
                            clientRequestId,
                            jobId,
                            jobKeyHash,
                            type,
                            index,
                            chunks.size(),
                            chunks.get(index))));
        }
        return List.copyOf(messages);
    }

    public MailInspectionDispatchMarkerMessage createMarker(
            MailInspectionSubmissionChunkMessage chunk) {
        Instant now = clock.instant();
        return new MailInspectionDispatchMarkerMessage(
                stableId(chunk.clientRequestId(), chunk.chunkIndex(), "marker"),
                MailInspectionRabbitNames.DISPATCH_MARKER_EVENT_TYPE,
                MailInspectionRabbitNames.DISPATCH_MARKER_SCHEMA_VERSION,
                now,
                chunk.traceId(),
                chunk.clientRequestId(),
                chunk.requestFingerprint(),
                chunk.jobId(),
                chunk.jobKeyHash(),
                chunk.inspectionType(),
                chunk.chunkIndex(),
                chunk.chunkCount(),
                chunk.businessConcurrency(),
                chunk.createdAt(),
                now);
    }

    private static String stableId(
            String clientRequestId,
            int chunkIndex,
            String purpose) {
        return UUID.nameUUIDFromBytes((purpose
                        + "\u0000"
                        + clientRequestId
                        + "\u0000"
                        + chunkIndex)
                .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static String safeTraceId(String fallback) {
        String value = MDC.get("traceId");
        if (value == null || value.isBlank() || value.length() > 128) {
            return fallback;
        }
        return value;
    }
}
