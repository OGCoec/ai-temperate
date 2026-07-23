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
    private static final String CLIENT_IP = "203.0.113.10";

    private AuthSessionSecretProtector protector;

    @BeforeEach
    void setUp() {
        protector = new AuthSessionSecretProtector(new HmacSha256Identifier(
                "auth-session-test-secret-0123456789".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void domainSeparatesLoginSubjectActorRefreshDeviceAndCsrf() {
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        LoginAttempt attempt = new LoginAttempt("person@example.test", DEVICE_ID, CLIENT_IP);

        ProtectedLoginAttempt protectedAttempt = protector.protect(attempt);
        HmacIdentifier loginSubject = protector.loginSubject("person@example.test");
        HmacIdentifier loginActor = protector.loginActor(DEVICE_ID, CLIENT_IP);
        HmacIdentifier loginNetwork = protector.loginNetwork(CLIENT_IP);
        HmacIdentifier refresh = protector.refreshToken(NANO_ID);
        HmacIdentifier device = protector.device(DEVICE_ID);
        HmacIdentifier deviceBlock = protector.deviceBlock(DEVICE_ID);
        HmacIdentifier protectedCsrf = protector.csrf(csrf);

        assertThat(protectedAttempt.identifierHash()).isEqualTo(loginSubject);
        assertThat(protectedAttempt.actorHash()).isEqualTo(loginActor);
        assertThat(protectedAttempt.networkHash()).isEqualTo(loginNetwork);
        assertThat(protectedAttempt.globalDeviceHash()).isEqualTo(deviceBlock);
        assertThat(Set.of(
                        loginSubject.value(),
                        loginActor.value(),
                        loginNetwork.value(),
                        refresh.value(),
                        device.value(),
                        deviceBlock.value(),
                        protectedCsrf.value()))
                .hasSize(7);
        assertThat(refresh).isNotEqualTo(device);
        assertThat(deviceBlock).isNotEqualTo(device);
        assertThat(loginActor).isNotEqualTo(loginNetwork);
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
        assertThatThrownBy(() -> protector.loginActor("short", CLIENT_IP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.loginActor(DEVICE_ID, "example.test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.refreshToken(NANO_ID.substring(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.device("hardware serial number"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.csrf(csrf + "="))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOnlyCanonicalRfc5952Ipv6LiteralsWithoutActorPartInjection() {
        assertThat(protector.loginActor(DEVICE_ID, "::1").value())
                .matches("^[A-Za-z0-9_-]{43}$");
        assertThat(protector.loginActor(DEVICE_ID, "2001:db8::1").value())
                .matches("^[A-Za-z0-9_-]{43}$");
        assertThat(protector.loginActor(DEVICE_ID, "2001:db8:0:1:2:3:4:5").value())
                .matches("^[A-Za-z0-9_-]{43}$");

        assertThatThrownBy(() -> protector.loginActor(DEVICE_ID, ":::"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.loginActor(DEVICE_ID, "1:::2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.loginActor(DEVICE_ID, "2001:db8::1::"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.loginActor(DEVICE_ID, "2001:DB8::1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.loginActor(DEVICE_ID, "2001:0db8::1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.loginActor(
                        DEVICE_ID, "2001:db8:0:0:0:0:0:1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.loginActor(
                        DEVICE_ID, "2001:db8::1:1:1:1:1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.loginActor(
                        DEVICE_ID + Character.toString(0) + "split", "::1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.loginActor(
                        DEVICE_ID, "::1" + Character.toString(0) + "split"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ipv4AcceptsOnlyAsciiDigitsAndNetworkHasItsOwnDomain() {
        HmacIdentifier actor = protector.loginActor(DEVICE_ID, "127.0.0.1");
        HmacIdentifier network = protector.loginNetwork("127.0.0.1");

        assertThat(network).isNotEqualTo(actor);
        assertThatThrownBy(() -> protector.loginNetwork("１２７.０.０.１"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.loginNetwork("一二七.零.零.一"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sensitiveInputsDoNotAppearInObjectRepresentations() {
        LoginAttempt attempt = new LoginAttempt("person@example.test", DEVICE_ID, CLIENT_IP);
        ProtectedLoginAttempt protectedAttempt = protector.protect(attempt);

        assertThat(attempt.toString())
                .doesNotContain("person@example.test", DEVICE_ID, CLIENT_IP);
        assertThat(protectedAttempt.toString())
                .doesNotContain("person@example.test", DEVICE_ID, CLIENT_IP)
                .contains("redacted");
        assertThat(protector.toString())
                .doesNotContain("auth-session-test-secret");
    }
}
