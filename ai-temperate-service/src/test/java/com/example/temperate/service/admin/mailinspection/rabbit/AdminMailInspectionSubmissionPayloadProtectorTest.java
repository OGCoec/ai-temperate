package com.example.temperate.service.admin.mailinspection.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Submission 多凭证载荷使用 AES-GCM 保护，并由提交身份 AAD 阻止跨幂等请求替换。
 */
final class AdminMailInspectionSubmissionPayloadProtectorTest {

    @Test
    void roundTripKeepsSensitiveFieldsOutOfEnvelopeAndDebugText() {
        MailInspectionSubmissionChunkMessage message = message();
        AdminMailInspectionSubmissionPayloadProtector protector =
                new AdminMailInspectionSubmissionPayloadProtector(
                        AdminMailInspectionProperties.defaults());

        List<MailInspectionSubmissionCredential> restored =
                protector.unprotect(message);

        assertThat(restored).hasSize(1);
        assertThat(restored.getFirst().refreshToken())
                .isEqualTo("refresh-secret");
        assertThat(message.toString())
                .doesNotContain(
                        "owner@example.test",
                        "11111111-1111-1111-1111-111111111111",
                        "refresh-secret");
    }

    @Test
    void changingClientRequestIdBreaksAadValidation() {
        MailInspectionSubmissionChunkMessage original = message();
        MailInspectionSubmissionChunkMessage tampered =
                new MailInspectionSubmissionChunkMessage(
                        original.messageId(),
                        original.eventType(),
                        original.schemaVersion(),
                        original.occurredAt(),
                        original.traceId(),
                        "650e8400-e29b-41d4-a716-446655440000",
                        original.requestFingerprint(),
                        original.jobId(),
                        original.jobKeyHash(),
                        original.inspectionType(),
                        original.chunkIndex(),
                        original.chunkCount(),
                        original.requestedCount(),
                        original.acceptedCount(),
                        original.duplicateCount(),
                        original.invalidCount(),
                        original.businessConcurrency(),
                        original.createdAt(),
                        original.protectedPayload());

        assertThatThrownBy(() ->
                new AdminMailInspectionSubmissionPayloadProtector(
                        AdminMailInspectionProperties.defaults())
                        .unprotect(tampered))
                .isInstanceOf(MailInspectionPayloadException.class);
    }

    private static MailInspectionSubmissionChunkMessage message() {
        AdminMailInspectionProperties properties =
                AdminMailInspectionProperties.defaults();
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-28T10:00:00Z"),
                ZoneOffset.UTC);
        MailInspectionSubmissionMessageFactory factory =
                new MailInspectionSubmissionMessageFactory(
                        new MailInspectionSubmissionChunker(properties),
                        new AdminMailInspectionSubmissionPayloadProtector(
                                properties),
                        clock);
        return factory.createChunks(
                        "550e8400-e29b-41d4-a716-446655440000",
                        new MailInspectionRequestFingerprint("A".repeat(43)),
                        "AZ9nEjRWeJCrze8SNFZ4kA",
                        "B".repeat(43),
                        MailInspectionType.OPENAI_STATUS,
                        1,
                        1,
                        0,
                        0,
                        4,
                        clock.instant(),
                        List.of(new MailboxCredential(
                                1,
                                "owner@example.test",
                                "11111111-1111-1111-1111-111111111111",
                                "refresh-secret")))
                .getFirst();
    }
}
