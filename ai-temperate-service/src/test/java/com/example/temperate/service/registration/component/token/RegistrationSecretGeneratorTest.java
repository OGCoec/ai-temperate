package com.example.temperate.service.registration.component.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.registration.component.token.impl.SecureRegistrationTokenGenerator;
import com.example.temperate.service.registration.verification.generator.impl.SecureVerificationCodeGenerator;
import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 验证注册令牌、流程 CSRF 和挑战句柄生成格式及随机性的测试。
 */
class RegistrationSecretGeneratorTest {

    @Test
    void generatesThirtyEightCharacterHutoolNanoIdRegisterTokens() {
        RegistrationTokenGenerator generator = new SecureRegistrationTokenGenerator();

        String first = generator.newRegisterToken();
        String second = generator.newRegisterToken();

        assertThat(first).hasSize(38).matches("^[A-Za-z0-9_-]{38}$");
        assertThat(second).hasSize(38).matches("^[A-Za-z0-9_-]{38}$").isNotEqualTo(first);
    }

    @Test
    void csrfAndChallengeAreUnpaddedBase64UrlOfThirtyTwoRandomBytes() {
        RegistrationTokenGenerator generator = new SecureRegistrationTokenGenerator();

        assertRandomBytes(generator.newFlowCsrf());
        assertRandomBytes(generator.newChallengeHandle());
    }

    @Test
    void verificationCodeIsSixDigitsAndUsesSecureRandom() throws Exception {
        SecureVerificationCodeGenerator generator = new SecureVerificationCodeGenerator();

        assertThat(generator.generate()).matches("^[0-9]{6}$");
        Field randomField = SecureVerificationCodeGenerator.class.getDeclaredField("secureRandom");
        assertThat(randomField.getType()).isEqualTo(SecureRandom.class);
    }

    private static void assertRandomBytes(String value) {
        assertThat(value).doesNotContain("=").matches("^[A-Za-z0-9_-]{43}$");
        assertThat(Base64.getUrlDecoder().decode(value)).hasSize(32);
    }
}
