package com.example.temperate.service.registration.flow.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.flow.domain.RegistrationActor;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 验证注册访问材料 HMAC 保护、业务域隔离和设备边界校验的测试。
 */
class RegistrationTokenProtectorTest {

    private static final String DEVICE_ID = "550e8400-e29b-41d4-a716-446655440000";

    private final HmacSha256Identifier hmac = new HmacSha256Identifier(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private final AuthSessionSecretProtector authProtector =
            new AuthSessionSecretProtector(hmac);
    private final RegistrationTokenProtector protector =
            new RegistrationTokenProtector(hmac, authProtector);

    @Test
    void protectsEverySensitiveFlowIdentifierWithPurposeSeparatedHmac() {
        RegistrationAccess access = new RegistrationAccess(
                "register-token", "flow-csrf", "challenge", DEVICE_ID, "203.0.113.7");

        ProtectedRegistrationAccess protectedAccess = protector.protect(access);

        assertThat(protectedAccess.flowId())
                .isEqualTo(hmac.identify(nul("register:flow", "register-token")));
        assertThat(protectedAccess.flowCsrfHash())
                .isEqualTo(hmac.identify(nul("register:csrf", "flow-csrf")));
        assertThat(protectedAccess.challengeId())
                .isEqualTo(hmac.identify(nul("register:challenge", "challenge")));
        assertThat(protectedAccess.deviceHash())
                .isEqualTo(hmac.identify(nul("register:device", DEVICE_ID)));
        assertThat(protectedAccess.globalDeviceHash())
                .isEqualTo(authProtector.deviceBlock(DEVICE_ID));
        assertThat(protectedAccess.ipHash())
                .isEqualTo(hmac.identify(nul("register:request-binding", DEVICE_ID)));
        assertThat(protectedAccess.emailCodeId())
                .isEqualTo(hmac.identify(nul("register:email-code", "register-token")));
        assertThat(protectedAccess.phoneCodeId())
                .isEqualTo(hmac.identify(nul("register:phone-code", "register-token")));
    }

    @Test
    void actorUsesOnlyInstallationIdAndDoesNotChangeWithIp() {
        RegistrationActor first = protector.protectActor(DEVICE_ID, "203.0.113.7");
        RegistrationActor second = protector.protectActor(DEVICE_ID, "198.51.100.9");

        assertThat(first).isEqualTo(second);
        assertThat(first.actorId())
                .isEqualTo(hmac.identify(nul("register:device", DEVICE_ID)));
        assertThat(first.globalDeviceHash())
                .isEqualTo(authProtector.deviceBlock(DEVICE_ID));
    }

    @Test
    void codeDigestBindsFlowChannelAndCode() {
        assertThat(protector.codeDigest("register-token", VerificationChannel.EMAIL, "012345"))
                .isEqualTo(hmac.identify(
                        nul("register:code", "register-token", "EMAIL", "012345")));
    }

    @Test
    void diagnosticFingerprintsUseIndependentDomainsWithoutChangingFlowBinding() {
        assertThat(protector.turnstileResponseDigest("turnstile-token"))
                .isEqualTo(hmac.identify(
                        nul("register:turnstile-response", "turnstile-token")));
        assertThat(protector.clientIpDiagnosticDigest("203.0.113.7"))
                .isEqualTo(hmac.identify(
                        nul("register:diagnostic-ip", "203.0.113.7")));
    }

    private static String nul(String... parts) {
        return String.join(Character.toString(0), parts);
    }
}
