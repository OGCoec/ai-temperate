package com.example.temperate.service.admin.mailinspection.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证管理员邮箱检查四段凭证解析、逐行错误分类、重复邮箱处理和敏感字段脱敏边界。
 */
final class MailboxCredentialParserTest {

    private final MailboxCredentialParser parser =
            new MailboxCredentialParser(AdminMailInspectionProperties.defaults());

    @Test
    void parsesExactFourPartCredentialWithoutKeepingPassword() {
        MailboxCredentialParseBatch batch = parser.parse(List.of(
                "owner@example.test----unused-password----"
                        + "11111111-1111-1111-1111-111111111111----refresh-value"));

        assertThat(batch.credentials()).hasSize(1);
        assertThat(batch.credentials().getFirst().email()).isEqualTo("owner@example.test");
        assertThat(batch.credentials().getFirst().clientId())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(batch.credentials().getFirst().toString()).doesNotContain("refresh-value");
        assertThat(batch.immediateResults()).isEmpty();
    }

    @Test
    void classifiesInvalidAndDuplicateLinesWithoutSendingThemToOauth() {
        MailboxCredentialParseBatch batch = parser.parse(List.of(
                "three----parts----only",
                "bad-address----password----11111111-1111-1111-1111-111111111111----token",
                "owner@example.test--------11111111-1111-1111-1111-111111111111----token",
                "owner@example.test----password----not-a-uuid----token",
                "owner@example.test----password----11111111-1111-1111-1111-111111111111----token",
                "OWNER@example.test----password----11111111-1111-1111-1111-111111111111----token"));

        assertThat(batch.credentials()).hasSize(1);
        assertThat(batch.immediateResults())
                .extracting(result -> result.status())
                .containsExactly(
                        MailInspectionResultStatus.INVALID_CREDENTIAL_FORMAT,
                        MailInspectionResultStatus.INVALID_EMAIL,
                        MailInspectionResultStatus.INVALID_PASSWORD_FIELD,
                        MailInspectionResultStatus.INVALID_CLIENT_ID,
                        MailInspectionResultStatus.DUPLICATE_EMAIL);
        assertThat(batch.duplicateCount()).isEqualTo(1);
        assertThat(batch.invalidCount()).isEqualTo(4);
    }

    @Test
    void rejectsRequestWhoseUtf8PayloadExceedsBound() {
        String oversized = "a".repeat(1_048_577);

        assertThatThrownBy(() -> parser.parse(List.of(oversized)))
                .isInstanceOf(AdminException.class);
    }

    @Test
    void acceptsMoreThanOneHundredCredentialsWithinByteBoundary() {
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            lines.add("owner" + index + "@example.test----unused-password----"
                    + "11111111-1111-1111-1111-111111111111----refresh-" + index);
        }

        MailboxCredentialParseBatch batch = parser.parse(lines);

        assertThat(batch.requestedCount()).isEqualTo(1_000);
        assertThat(batch.credentials()).hasSize(1_000);
        assertThat(batch.credentials().getLast().lineNumber()).isEqualTo(1_000);
    }
}
