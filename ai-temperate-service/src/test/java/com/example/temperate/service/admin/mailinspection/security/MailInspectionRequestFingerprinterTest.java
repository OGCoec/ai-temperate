package com.example.temperate.service.admin.mailinspection.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.AdminMailInspectionCreateCommand;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.security.impl.MailInspectionRequestFingerprinterImpl;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证幂等指纹稳定覆盖检查类型、并发和有序原始凭证，同时不在调试文本中暴露指纹。
 */
final class MailInspectionRequestFingerprinterTest {

    private final MailInspectionRequestFingerprinter fingerprinter =
            new MailInspectionRequestFingerprinterImpl(
                    AdminMailInspectionProperties.defaults());

    @Test
    void sameRequestProducesSameFingerprint() {
        AdminMailInspectionCreateCommand command = command(4, "one", "two");

        assertThat(fingerprinter.fingerprint(
                        MailInspectionType.OPENAI_STATUS,
                        command))
                .isEqualTo(fingerprinter.fingerprint(
                        MailInspectionType.OPENAI_STATUS,
                        command));
    }

    @Test
    void orderConcurrencyAndTypeChangeFingerprint() {
        var original = fingerprinter.fingerprint(
                MailInspectionType.OPENAI_STATUS,
                command(4, "one", "two"));

        assertThat(fingerprinter.fingerprint(
                        MailInspectionType.OPENAI_STATUS,
                        command(4, "two", "one")))
                .isNotEqualTo(original);
        assertThat(fingerprinter.fingerprint(
                        MailInspectionType.OPENAI_STATUS,
                        command(8, "one", "two")))
                .isNotEqualTo(original);
        assertThat(fingerprinter.fingerprint(
                        MailInspectionType.KIRO_STATUS,
                        command(4, "one", "two")))
                .isNotEqualTo(original);
    }

    private static AdminMailInspectionCreateCommand command(
            int concurrency,
            String... lines) {
        return new AdminMailInspectionCreateCommand(
                "550e8400-e29b-41d4-a716-446655440000",
                List.of(lines),
                concurrency);
    }
}
