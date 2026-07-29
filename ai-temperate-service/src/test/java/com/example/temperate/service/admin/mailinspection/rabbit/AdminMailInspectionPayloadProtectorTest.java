package com.example.temperate.service.admin.mailinspection.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import org.junit.jupiter.api.Test;

/**
 * 验证进入 RabbitMQ 的邮箱凭证使用独立 AES-256-GCM 密钥保护，并由消息身份 AAD 防止跨任务替换。
 */
final class AdminMailInspectionPayloadProtectorTest {

    private static final String JOB_ID = "AZ9nEjRWeJCrze8SNFZ4kA";
    private static final String JOB_HASH = "B".repeat(43);

    private final AdminMailInspectionPayloadProtector protector =
            new AdminMailInspectionPayloadProtector(
                    AdminMailInspectionProperties.defaults());

    @Test
    void protectsOnlyFieldsRequiredByOAuthAndImap() {
        MailboxCredential credential = new MailboxCredential(
                7,
                "owner@example.test",
                "11111111-1111-1111-1111-111111111111",
                "sensitive-refresh-token");

        MailInspectionProtectedPayload payload = protector.protect(
                "message-1",
                JOB_ID,
                JOB_HASH,
                MailInspectionType.OPENAI_STATUS,
                credential);
        MailInspectionProtectedCredential restored = protector.unprotect(
                "message-1",
                JOB_ID,
                JOB_HASH,
                MailInspectionType.OPENAI_STATUS,
                7,
                payload);

        assertThat(restored.email()).isEqualTo("owner@example.test");
        assertThat(restored.clientId())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(restored.refreshToken()).isEqualTo("sensitive-refresh-token");
        assertThat(payload.toString())
                .doesNotContain("owner@example.test", "sensitive-refresh-token");
    }

    @Test
    void rejectsPayloadMovedToAnotherLineOrJob() {
        MailboxCredential credential = new MailboxCredential(
                7,
                "owner@example.test",
                "11111111-1111-1111-1111-111111111111",
                "sensitive-refresh-token");
        MailInspectionProtectedPayload payload = protector.protect(
                "message-1",
                JOB_ID,
                JOB_HASH,
                MailInspectionType.OPENAI_STATUS,
                credential);

        assertThatThrownBy(() -> protector.unprotect(
                "message-1",
                "BZ9nEjRWeJCrze8SNFZ4kA",
                JOB_HASH,
                MailInspectionType.OPENAI_STATUS,
                7,
                payload))
                .isInstanceOf(MailInspectionPayloadException.class);
        assertThatThrownBy(() -> protector.unprotect(
                "message-1",
                JOB_ID,
                JOB_HASH,
                MailInspectionType.OPENAI_STATUS,
                8,
                payload))
                .isInstanceOf(MailInspectionPayloadException.class);
        assertThatThrownBy(() -> protector.unprotect(
                "message-1",
                JOB_ID,
                "C".repeat(43),
                MailInspectionType.OPENAI_STATUS,
                7,
                payload))
                .isInstanceOf(MailInspectionPayloadException.class);
    }
}
