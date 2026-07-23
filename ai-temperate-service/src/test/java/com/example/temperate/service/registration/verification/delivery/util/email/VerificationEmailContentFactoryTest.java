package com.example.temperate.service.registration.verification.delivery.util.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.registration.verification.delivery.dto.VerificationPurpose;
import org.junit.jupiter.api.Test;

/**
 * 验证 Gmail 和 Microsoft Graph 共用的邮件内容不会因供应商不同而产生模板偏差。
 */
class VerificationEmailContentFactoryTest {

    @Test
    void createsPurposeSpecificSubjectAndBodyWithExactCode() {
        VerificationEmailContent content = VerificationEmailContentFactory.create(
                VerificationPurpose.REGISTRATION, "012345");

        assertThat(content.subject()).isEqualTo("注册验证码");
        assertThat(content.body()).isEqualTo("您的注册验证码是 012345，5 分钟内有效。");
    }

    @Test
    void rejectsCodeThatIsNotExactlySixDigits() {
        assertThatThrownBy(() -> VerificationEmailContentFactory.create(
                        VerificationPurpose.LOGIN, "12345"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
