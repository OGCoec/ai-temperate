package com.example.temperate.service.auth.password.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.auth.enums.PasswordStrengthLevel;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 验证服务端密码评估严格遵守 SHOPPING_V1 五档契约及 BCrypt 字节边界。
 */
class PasswordStrengthPolicyTest {

    private final PasswordStrengthPolicy policy = new PasswordStrengthPolicy();

    @ParameterizedTest
    @MethodSource("contractCases")
    void assessesShoppingV1Contract(
            String password,
            PasswordStrengthLevel expectedLevel,
            int expectedScore,
            boolean expectedAcceptable) {
        PasswordStrengthAssessment assessment = policy.assess(password);

        assertThat(assessment.level()).isEqualTo(expectedLevel);
        assertThat(assessment.score()).isEqualTo(expectedScore);
        assertThat(assessment.acceptable()).isEqualTo(expectedAcceptable);
    }

    @Test
    void preservesOriginalShoppingMediumFallback() {
        assertThat(policy.assess("!!!!!!!").level()).isEqualTo(PasswordStrengthLevel.MEDIUM);
        assertThat(policy.assess("中文密码示例甲").level()).isEqualTo(PasswordStrengthLevel.MEDIUM);
    }

    @Test
    void rejectsMoreThanSeventyTwoUtf8BytesWithoutChangingTheLevel() {
        String atLimit = "Aa1!" + "a".repeat(68);
        String overLimit = atLimit + "a";

        assertThat(policy.assess(atLimit).utf8Bytes()).isEqualTo(72);
        assertThat(policy.assess(atLimit).acceptable()).isTrue();
        assertThat(policy.assess(overLimit).level()).isEqualTo(PasswordStrengthLevel.VERY_STRONG);
        assertThat(policy.assess(overLimit).utf8Bytes()).isEqualTo(73);
        assertThat(policy.assess(overLimit).acceptable()).isFalse();
    }

    @Test
    void validatesStrengthAndConfirmationBeforePasswordWrites() {
        assertThatThrownBy(() -> policy.validateForWrite("1234567", "1234567"))
                .isInstanceOfSatisfying(PasswordValidationException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(
                                PasswordValidationException.Reason.STRENGTH_INSUFFICIENT));
        assertThatThrownBy(() -> policy.validateForWrite("abc123?", "abc123!"))
                .isInstanceOfSatisfying(PasswordValidationException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(
                                PasswordValidationException.Reason.CONFIRMATION_MISMATCH));
        assertThat(policy.validateForWrite("abc123?", "abc123?").level())
                .isEqualTo(PasswordStrengthLevel.MEDIUM);
    }

    private static Stream<Arguments> contractCases() {
        return Stream.of(
                Arguments.of("", PasswordStrengthLevel.NONE, 0, false),
                Arguments.of("Aa1!aa", PasswordStrengthLevel.NONE, 0, false),
                Arguments.of("1234567", PasswordStrengthLevel.WEAK, 1, false),
                Arguments.of("abc123?", PasswordStrengthLevel.MEDIUM, 2, true),
                Arguments.of("abcDEF123", PasswordStrengthLevel.STRONG, 3, true),
                Arguments.of("abcDEF12!", PasswordStrengthLevel.VERY_STRONG, 4, true),
                Arguments.of("Aa63.58516", PasswordStrengthLevel.VERY_STRONG, 4, true));
    }
}
