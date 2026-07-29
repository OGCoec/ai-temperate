package com.example.temperate.web.admin.mailinspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import org.junit.jupiter.api.Test;

/**
 * 验证创建任务幂等键只接受规范小写 UUIDv4。
 */
final class MailInspectionClientRequestIdTest {

    @Test
    void acceptsCanonicalLowercaseUuidV4() {
        assertThat(MailInspectionClientRequestId.parse(
                "550e8400-e29b-41d4-a716-446655440000").value())
                .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    void rejectsUppercaseAndNonV4Values() {
        assertInvalid("550E8400-E29B-41D4-A716-446655440000");
        assertInvalid("550e8400-e29b-11d4-a716-446655440000");
    }

    private static void assertInvalid(String value) {
        assertThatThrownBy(() -> MailInspectionClientRequestId.parse(value))
                .isInstanceOfSatisfying(
                        AdminException.class,
                        exception -> assertThat(exception.code()).isEqualTo(
                                AdminErrorCode
                                        .ADMIN_MAIL_INSPECTION_IDEMPOTENCY_KEY_INVALID));
    }
}
