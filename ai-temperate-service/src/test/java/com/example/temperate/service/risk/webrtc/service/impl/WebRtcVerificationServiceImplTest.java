package com.example.temperate.service.risk.webrtc.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcWriteResult;
import com.example.temperate.service.risk.preauth.store.PreAuthStore;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationOutcome;
import com.example.temperate.service.risk.webrtc.security.WebRtcIpProtector;
import com.example.temperate.service.risk.webrtc.validation.WebRtcIpNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 验证 WebRTC 三态解释、可信 HTTP IP 匹配以及迟到结果的并发优先级。
 */
class WebRtcVerificationServiceImplTest {

    private static final String HTTP_IP = "8.8.8.8";
    private static final HmacIdentifier TOKEN = digest('T');
    private static final HmacIdentifier DEVICE = digest('D');

    @Test
    void sameIpVerifiedStateAllowsWithoutWritingAgain() {
        Fixture fixture = fixture();
        String ciphertext = fixture.protector().encrypt(
                List.of(HTTP_IP),
                RiskScope.USER,
                TOKEN,
                fixture.httpIpDigest());
        PreAuthAccess access = access(fixture, true, ciphertext);

        assertThat(fixture.service().inspect(access, HTTP_IP).outcome())
                .isEqualTo(WebRtcVerificationOutcome.VERIFIED);
    }

    @Test
    void falseStateSeparatesMismatchFromEmptyVerificationFailure() {
        Fixture fixture = fixture();
        String mismatch = fixture.protector().encrypt(
                List.of("1.1.1.1"),
                RiskScope.USER,
                TOKEN,
                fixture.httpIpDigest());
        String empty = fixture.protector().encrypt(
                List.of(),
                RiskScope.USER,
                TOKEN,
                fixture.httpIpDigest());

        assertThat(fixture.service().inspect(
                        access(fixture, false, mismatch),
                        HTTP_IP).outcome())
                .isEqualTo(WebRtcVerificationOutcome.IP_MISMATCH);
        assertThat(fixture.service().inspect(
                        access(fixture, false, empty),
                        HTTP_IP).outcome())
                .isEqualTo(WebRtcVerificationOutcome.VERIFICATION_FAILED);
    }

    @Test
    void changedHttpIpRequiresNewProbeBeforeAcceptingAReport() {
        Fixture fixture = fixture();
        PreAuthAccess access = access(fixture, true, fixture.protector().encrypt(
                List.of(HTTP_IP),
                RiskScope.USER,
                TOKEN,
                fixture.httpIpDigest()));

        assertThat(fixture.service().inspect(access, "1.1.1.1").outcome())
                .isEqualTo(WebRtcVerificationOutcome.VERIFICATION_REQUIRED);
        assertThat(fixture.service().report(
                        access,
                        "1.1.1.1",
                        List.of("1.1.1.1")).outcome())
                .isEqualTo(WebRtcVerificationOutcome.NETWORK_CHANGED);
    }

    @Test
    void matchingReportWritesEncryptedIpsAndCanUpgradeFalseToTrue() {
        Fixture fixture = fixture();
        PreAuthAccess access = access(fixture, false, fixture.protector().encrypt(
                List.of(),
                RiskScope.USER,
                TOKEN,
                fixture.httpIpDigest()));
        when(fixture.store().writeWebRtcResult(
                        eq(RiskScope.USER),
                        eq(TOKEN),
                        eq(DEVICE),
                        eq(fixture.httpIpDigest()),
                        eq(true),
                        any(String.class),
                        eq(true),
                        eq(Duration.ofMinutes(30))))
                .thenReturn(PreAuthWebRtcWriteResult.UPDATED);

        var decision = fixture.service().report(
                access,
                HTTP_IP,
                List.of("::ffff:8.8.8.8", "2606:4700:4700::1111"));

        assertThat(decision.outcome()).isEqualTo(WebRtcVerificationOutcome.VERIFIED);
        verify(fixture.store()).writeWebRtcResult(
                eq(RiskScope.USER),
                eq(TOKEN),
                eq(DEVICE),
                eq(fixture.httpIpDigest()),
                eq(true),
                any(String.class),
                eq(true),
                eq(Duration.ofMinutes(30)));
    }

    @Test
    void corruptCiphertextIsClearedAndRequiresVerificationAgain() {
        Fixture fixture = fixture();
        PreAuthAccess access = access(fixture, true, "v1.invalid.invalid");
        when(fixture.store().clearWebRtcResult(
                        RiskScope.USER,
                        TOKEN,
                        DEVICE,
                        fixture.httpIpDigest(),
                        Duration.ofMinutes(30)))
                .thenReturn(true);

        assertThat(fixture.service().inspect(access, HTTP_IP).outcome())
                .isEqualTo(WebRtcVerificationOutcome.VERIFICATION_REQUIRED);
        verify(fixture.store()).clearWebRtcResult(
                RiskScope.USER,
                TOKEN,
                DEVICE,
                fixture.httpIpDigest(),
                Duration.ofMinutes(30));
    }

    @Test
    void lateFailureCannotDowngradeAnAlreadyVerifiedState() {
        Fixture fixture = fixture();
        PreAuthAccess initial = access(fixture, null, null);
        String verifiedCiphertext = fixture.protector().encrypt(
                List.of(HTTP_IP),
                RiskScope.USER,
                TOKEN,
                fixture.httpIpDigest());
        PreAuthState verifiedState = state(
                fixture,
                true,
                verifiedCiphertext);
        when(fixture.store().writeWebRtcResult(
                        eq(RiskScope.USER),
                        eq(TOKEN),
                        eq(DEVICE),
                        eq(fixture.httpIpDigest()),
                        eq(false),
                        any(String.class),
                        eq(false),
                        eq(Duration.ofMinutes(30))))
                .thenReturn(PreAuthWebRtcWriteResult.VERIFIED_PRESERVED);
        when(fixture.store().find(RiskScope.USER, TOKEN))
                .thenReturn(Optional.of(verifiedState));

        assertThat(fixture.service().report(initial, HTTP_IP, List.of()).outcome())
                .isEqualTo(WebRtcVerificationOutcome.VERIFIED);
    }

    private static Fixture fixture() {
        byte[] hmacKey = "abcdef0123456789abcdef0123456789"
                .getBytes(StandardCharsets.UTF_8);
        NetworkRiskIdentifier identifier = new NetworkRiskIdentifier(
                new HmacSha256Identifier(hmacKey));
        HmacIdentifier ipDigest = identifier.identifyIp(HTTP_IP);
        String encryptionKey = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8));
        WebRtcIpProtector protector = new WebRtcIpProtector(
                encryptionKey,
                new ObjectMapper());
        PreAuthStore store = mock(PreAuthStore.class);
        NetworkRiskProperties properties = mock(NetworkRiskProperties.class);
        when(properties.webRtc()).thenReturn(new NetworkRiskProperties.WebRtc(
                Duration.ofSeconds(15),
                List.of(
                        URI.create("stun:stun.l.google.com:19302"),
                        URI.create("stun:stun.cloudflare.com:3478"),
                        URI.create("stun:global.stun.twilio.com:3478"),
                        URI.create("stun:stun.nextcloud.com:3478")),
                8,
                encryptionKey));
        when(properties.anonymousPreAuthTtl()).thenReturn(Duration.ofMinutes(30));
        when(properties.authenticatedPreAuthTtl()).thenReturn(Duration.ofHours(6));
        WebRtcVerificationServiceImpl service = new WebRtcVerificationServiceImpl(
                properties,
                store,
                identifier,
                protector,
                new WebRtcIpNormalizer());
        return new Fixture(service, store, identifier, protector, ipDigest);
    }

    private static PreAuthAccess access(
            Fixture fixture,
            Boolean status,
            String ciphertext) {
        return new PreAuthAccess(TOKEN, state(fixture, status, ciphertext));
    }

    private static PreAuthState state(
            Fixture fixture,
            Boolean status,
            String ciphertext) {
        PreAuthState state = mock(PreAuthState.class);
        when(state.scope()).thenReturn(RiskScope.USER);
        when(state.deviceDigest()).thenReturn(DEVICE);
        when(state.currentIpDigest()).thenReturn(fixture.httpIpDigest());
        when(state.webRtcStatus()).thenReturn(status);
        when(state.webRtcIps()).thenReturn(ciphertext);
        when(state.authenticated()).thenReturn(false);
        return state;
    }

    private static HmacIdentifier digest(char value) {
        return HmacIdentifier.fromProtectedValue(String.valueOf(value).repeat(43));
    }

    private record Fixture(
            WebRtcVerificationServiceImpl service,
            PreAuthStore store,
            NetworkRiskIdentifier identifier,
            WebRtcIpProtector protector,
            HmacIdentifier httpIpDigest) {
    }
}
