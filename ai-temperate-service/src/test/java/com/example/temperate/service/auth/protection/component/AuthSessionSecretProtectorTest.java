package com.example.temperate.service.auth.protection.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.login.limit.dto.LoginAttempt;
import com.example.temperate.service.auth.login.limit.dto.ProtectedLoginAttempt;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证认证会话材料的格式校验、域隔离 HMAC 和 CSRF 规范化约束。
 */
class AuthSessionSecretProtectorTest {

    private static final String DEVICE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String NANO_ID = "A2345678901234567890123456789012345678";
    private AuthSessionSecretProtector protector;

    @BeforeEach
    void setUp() {
        protector = new AuthSessionSecretProtector(new HmacSha256Identifier(
                "auth-session-test-secret-0123456789".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void domainSeparatesLoginSubjectActorRefreshDeviceAndCsrf() {
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        LoginAttempt attempt = new LoginAttempt("person@example.test", DEVICE_ID);

        ProtectedLoginAttempt protectedAttempt = protector.protect(attempt);
        HmacIdentifier loginSubject = protector.loginSubject("person@example.test");
        HmacIdentifier loginActor = protector.loginActor(DEVICE_ID);
        HmacIdentifier refresh = protector.refreshToken(NANO_ID);
        HmacIdentifier device = protector.device(DEVICE_ID);
        HmacIdentifier deviceBlock = protector.deviceBlock(DEVICE_ID);
        HmacIdentifier protectedCsrf = protector.csrf(csrf);

        assertThat(protectedAttempt.identifierHash()).isEqualTo(loginSubject);
        assertThat(protectedAttempt.actorHash()).isEqualTo(loginActor);
        assertThat(protectedAttempt.globalDeviceHash()).isEqualTo(deviceBlock);
        assertThat(Set.of(
                        loginSubject.value(),
                        loginActor.value(),
                        refresh.value(),
                        device.value(),
                        deviceBlock.value(),
                        protectedCsrf.value()))
                .hasSize(6);
        assertThat(refresh).isNotEqualTo(device);
        assertThat(deviceBlock).isNotEqualTo(device);
    }

    @Test
    void acceptsOnlyNormalizedLoginAndCanonicalOpaqueCredentialFormats() {
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

        assertThat(protector.loginSubject("+15551234567").value())
                .matches("^[A-Za-z0-9_-]{43}$");
        assertThat(protector.csrf(csrf).value()).matches("^[A-Za-z0-9_-]{43}$");

        assertThatThrownBy(() -> protector.loginSubject("Person@Example.Test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.loginSubject("15551234567"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.loginActor("short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.refreshToken(NANO_ID.substring(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.device("hardware serial number"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.csrf(csrf + "="))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sensitiveInputsDoNotAppearInObjectRepresentations() {
        LoginAttempt attempt = new LoginAttempt("person@example.test", DEVICE_ID);
        ProtectedLoginAttempt protectedAttempt = protector.protect(attempt);

        assertThat(attempt.toString())
                .doesNotContain("person@example.test", DEVICE_ID);
        assertThat(protectedAttempt.toString())
                .doesNotContain("person@example.test", DEVICE_ID)
                .contains("redacted");
        assertThat(protector.toString())
                .doesNotContain("auth-session-test-secret");
    }
}
