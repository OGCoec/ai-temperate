package com.example.temperate.web.auth.phone;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.web.auth.login.controller.LoginController;
import com.example.temperate.web.auth.passwordreset.controller.PasswordResetController;
import com.example.temperate.web.auth.registration.controller.RegistrationController;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证认证入口 DTO 对手机号基础格式的第一层约束。
 *
 * <p>这些测试只覆盖 Web 入参边界，业务层仍需要在统一手机号规范化器里再次执行同一格式防线。</p>
 */
class PhoneRequestValidationTest {

    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abc123",
            "中文123",
            "123-456",
            "(415)5552671",
            "+12+3",
            "++123"
    })
    void rejectsMalformedPhoneNumbersAtDtoBoundary(String phoneNumber) {
        assertInvalid(new LoginController.PasswordLoginRequest(
                null, "US", phoneNumber, "password1"));
        assertInvalid(new LoginController.CodeStartRequest(
                LoginStrategyType.SMS_CODE, null, "US", phoneNumber));
        assertInvalid(new RegistrationController.StartRequest(
                "person@example.test", "US", phoneNumber));
        assertInvalid(new PasswordResetController.StartRequest(
                VerificationChannel.SMS, null, "US", phoneNumber));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4155552671", "+14155552671"})
    void allowsDigitOnlyAndLeadingPlusPhoneNumbersAtDtoBoundary(String phoneNumber) {
        assertValid(new LoginController.PasswordLoginRequest(
                null, "US", phoneNumber, "password1"));
        assertValid(new LoginController.CodeStartRequest(
                LoginStrategyType.SMS_CODE, null, "US", phoneNumber));
        assertValid(new RegistrationController.StartRequest(
                "person@example.test", "US", phoneNumber));
        assertValid(new PasswordResetController.StartRequest(
                VerificationChannel.SMS, null, "US", phoneNumber));
    }

    private static void assertInvalid(Object request) {
        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    private static void assertValid(Object request) {
        assertThat(VALIDATOR.validate(request)).isEmpty();
    }
}
