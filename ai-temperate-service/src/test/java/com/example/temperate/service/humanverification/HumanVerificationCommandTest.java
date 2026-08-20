package com.example.temperate.service.humanverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证统一命令只允许既定 Turnstile action，并保证 hCaptcha 命令永远携带非空的空 action。
 */
class HumanVerificationCommandTest {

    @ParameterizedTest
    @ValueSource(strings = {"register", "login", "password_reset", "oauth_phone"})
    void createsTurnstileCommandForSupportedAction(String action) {
        HumanVerificationCommand command = HumanVerificationCommand.turnstile(
                "token", "203.0.113.10", "challenge", action);

        assertThat(command.expectedAction()).isEqualTo(action);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "admin_login", "REGISTER"})
    void rejectsUnsupportedTurnstileAction(String action) {
        assertThatThrownBy(() -> HumanVerificationCommand.turnstile(
                "token", "203.0.113.10", "challenge", action))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"token-a", "token-b"})
    void createsHcaptchaCommandWithNonNullEmptyAction(String token) {
        HumanVerificationCommand command = HumanVerificationCommand.hcaptcha(
                token, "203.0.113.10", "challenge");

        assertThat(command.expectedAction()).isEmpty();
    }

    @Test
    void rejectsNullActionEvenWhenCanonicalConstructorIsUsed() {
        assertThatThrownBy(() -> new HumanVerificationCommand(
                "token",
                "203.0.113.10",
                "challenge",
                null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
