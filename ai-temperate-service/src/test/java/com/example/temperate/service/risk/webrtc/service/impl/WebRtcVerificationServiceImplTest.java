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
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcBeginResult;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcFailureReason;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
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
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 验证 WebRTC v6 完全异步门禁的 begin、临时放行、Redis 截止时间和 generation 写入语义。
 */
class WebRtcVerificationServiceImplTest {

    private static final String HTTP_IP = "8.8.8.8";
    private static final Instant DEADLINE = Instant.parse("2026-08-04T12:00:15Z");
    private static final HmacIdentifier TOKEN = digest('T');
    private static final HmacIdentifier DEVICE = digest('D');

    @Test
    void requiredAndPendingAllowBusinessRequestsWithoutStartingImplicitly() {
        Fixture fixture = fixture();
        PreAuthAccess required = access(
                fixture, PreAuthWebRtcPhase.REQUIRED, 7L, DEADLINE, null, null);
        PreAuthAccess pending = access(
                fixture, PreAuthWebRtcPhase.PENDING, 8L, DEADLINE, null, null);

        assertThat(fixture.service().inspect(required, HTTP_IP).outcome())
                .isEqualTo(WebRtcVerificationOutcome.VERIFICATION_REQUIRED);
        assertThat(fixture.service().inspect(pending, HTTP_IP).outcome())
                .isEqualTo(WebRtcVerificationOutcome.VERIFICATION_PENDING);
    }

    @Test
    void beginUsesRedisRemainingMillisAndStartsOnlyCurrentGeneration() {
        Fixture fixture = fixture();
        PreAuthAccess access = access(
                fixture, PreAuthWebRtcPhase.REQUIRED, 9L, DEADLINE, null, null);
        when(fixture.store().beginWebRtcVerification(
                RiskScope.USER,
                TOKEN,
                DEVICE,
                fixture.httpIpDigest(),
                9L,
                Duration.ofSeconds(15),
                Duration.ofMinutes(30)))
                .thenReturn(new PreAuthWebRtcBeginResult(
                        PreAuthWebRtcBeginResult.Status.STARTED,
                        9L,
                        DEADLINE,
                        15_000L));

        var decision = fixture.service().begin(access, HTTP_IP);

        assertThat(decision.outcome())
                .isEqualTo(WebRtcVerificationOutcome.VERIFICATION_PENDING);
        assertThat(decision.pendingRemainingMillis()).isEqualTo(15_000L);
    }

    @Test
    void expiredOpenStateReloadsTheRedisTerminalReason() {
        Fixture fixture = fixture();
        PreAuthAccess pending = access(
                fixture, PreAuthWebRtcPhase.PENDING, 10L, DEADLINE, null, null);
        PreAuthAccess failed = access(
                fixture,
                PreAuthWebRtcPhase.FAILED,
                10L,
                null,
                PreAuthWebRtcFailureReason.REPORT_TIMEOUT,
                null);
        when(fixture.store().expireWebRtcDeadline(
                RiskScope.USER,
                TOKEN,
                DEVICE,
                fixture.httpIpDigest(),
                10L,
                Duration.ofMinutes(30))).thenReturn(true);
        when(fixture.store().find(RiskScope.USER, TOKEN))
                .thenReturn(Optional.of(failed.state()));

        assertThat(fixture.service().inspect(pending, HTTP_IP).outcome())
                .isEqualTo(WebRtcVerificationOutcome.VERIFICATION_TIMEOUT);
        verify(fixture.store()).expireWebRtcDeadline(
                RiskScope.USER,
                TOKEN,
                DEVICE,
                fixture.httpIpDigest(),
                10L,
                Duration.ofMinutes(30));
    }

    @Test
    void matchingReportCanOnlyCompleteTheCurrentPendingGeneration() {
        Fixture fixture = fixture();
        PreAuthAccess access = access(
                fixture, PreAuthWebRtcPhase.PENDING, 11L, DEADLINE, null, null);
        expectWritten(
                fixture,
                11L,
                true,
                null);

        var decision = fixture.service().report(
                access,
                HTTP_IP,
                "11",
                List.of("::ffff:8.8.8.8"));

        assertThat(decision.outcome()).isEqualTo(WebRtcVerificationOutcome.VERIFIED);
    }

    @Test
    void ipv4ReportAcceptsTheSameSlash24WithADifferentLastByte() {
        Fixture fixture = fixture();
        PreAuthAccess access = access(
                fixture, PreAuthWebRtcPhase.PENDING, 12L, DEADLINE, null, null);
        expectWritten(fixture, 12L, true, null);

        var decision = fixture.service().report(
                access,
                HTTP_IP,
                "12",
                List.of("8.8.8.9"));

        assertThat(decision.outcome()).isEqualTo(WebRtcVerificationOutcome.VERIFIED);
    }

    @Test
    void ipv4ReportRejectsADifferentSlash24() {
        Fixture fixture = fixture();
        PreAuthAccess access = access(
                fixture, PreAuthWebRtcPhase.PENDING, 13L, DEADLINE, null, null);
        expectWritten(
                fixture,
                13L,
                false,
                PreAuthWebRtcFailureReason.IP_MISMATCH);

        var decision = fixture.service().report(
                access,
                HTTP_IP,
                "13",
                List.of("8.8.4.4"));

        assertThat(decision.outcome()).isEqualTo(WebRtcVerificationOutcome.IP_MISMATCH);
    }

    @Test
    void ipv6ReportAcceptsTheSameSlash64() {
        String httpIpv6 = "2606:4700:4700::1111";
        Fixture fixture = fixture(httpIpv6);
        PreAuthAccess access = access(
                fixture,
                PreAuthWebRtcPhase.PENDING,
                14L,
                DEADLINE,
                null,
                null);
        expectWritten(fixture, 14L, true, null);

        assertThat(fixture.service().report(
                access,
                httpIpv6,
                "14",
                List.of("2606:4700:4700::2222")).outcome())
                .isEqualTo(WebRtcVerificationOutcome.VERIFIED);
    }

    @Test
    void ipv6ReportRejectsAnotherSlash64() {
        String httpIpv6 = "2606:4700:4700::1111";
        Fixture fixture = fixture(httpIpv6);
        PreAuthAccess access = access(
                fixture,
                PreAuthWebRtcPhase.PENDING,
                15L,
                DEADLINE,
                null,
                null);
        expectWritten(
                fixture,
                15L,
                false,
                PreAuthWebRtcFailureReason.IP_MISMATCH);

        assertThat(fixture.service().report(
                access,
                httpIpv6,
                "15",
                List.of("2606:4700:4701::2222")).outcome())
                .isEqualTo(WebRtcVerificationOutcome.IP_MISMATCH);
    }

    @Test
    void reportAllowsOneCandidatePerIpVersionWhenTheHttpVersionMatches() {
        Fixture fixture = fixture();
        PreAuthAccess access = access(
                fixture, PreAuthWebRtcPhase.PENDING, 16L, DEADLINE, null, null);
        expectWritten(fixture, 16L, true, null);

        var decision = fixture.service().report(
                access,
                HTTP_IP,
                "16",
                List.of("8.8.8.9", "2606:4700:4700::1111"));

        assertThat(decision.outcome()).isEqualTo(WebRtcVerificationOutcome.VERIFIED);
    }

    @Test
    void reportRejectsMultipleDistinctIpv4Candidates() {
        Fixture fixture = fixture();
        PreAuthAccess access = access(
                fixture,
                PreAuthWebRtcPhase.PENDING,
                17L,
                DEADLINE,
                null,
                null);
        expectWritten(
                fixture,
                17L,
                false,
                PreAuthWebRtcFailureReason.IP_MISMATCH);

        assertThat(fixture.service().report(
                access,
                HTTP_IP,
                "17",
                List.of("8.8.8.8", "8.8.8.9")).outcome())
                .isEqualTo(WebRtcVerificationOutcome.IP_MISMATCH);
    }

    @Test
    void reportRejectsMultipleDistinctIpv6Candidates() {
        String httpIpv6 = "2606:4700:4700::1111";
        Fixture fixture = fixture(httpIpv6);
        PreAuthAccess access = access(
                fixture,
                PreAuthWebRtcPhase.PENDING,
                18L,
                DEADLINE,
                null,
                null);
        expectWritten(
                fixture,
                18L,
                false,
                PreAuthWebRtcFailureReason.IP_MISMATCH);

        assertThat(fixture.service().report(
                access,
                httpIpv6,
                "18",
                List.of(
                        "2606:4700:4700::1111",
                        "2606:4700:4700::2222")).outcome())
                .isEqualTo(WebRtcVerificationOutcome.IP_MISMATCH);
    }

    @Test
    void reportRejectsWhenOnlyTheOtherIpVersionIsPresent() {
        Fixture fixture = fixture();
        PreAuthAccess access = access(
                fixture, PreAuthWebRtcPhase.PENDING, 19L, DEADLINE, null, null);
        expectWritten(
                fixture,
                19L,
                false,
                PreAuthWebRtcFailureReason.IP_MISMATCH);

        var decision = fixture.service().report(
                access,
                HTTP_IP,
                "19",
                List.of("2606:4700:4700::1111"));

        assertThat(decision.outcome()).isEqualTo(WebRtcVerificationOutcome.IP_MISMATCH);
    }

    @Test
    void reportCannotImplicitlyStartARequiredGeneration() {
        Fixture fixture = fixture();
        PreAuthAccess access = access(
                fixture, PreAuthWebRtcPhase.REQUIRED, 20L, DEADLINE, null, null);

        assertThat(fixture.service().report(
                access,
                HTTP_IP,
                "20",
                List.of(HTTP_IP)).outcome())
                .isEqualTo(WebRtcVerificationOutcome.STALE_REPORT);
    }

    @Test
    void mismatchFailureRetainsEncryptedCandidatesWithoutADeadline() {
        Fixture fixture = fixture();
        String ciphertext = fixture.protector().encrypt(
                List.of("1.1.1.1"),
                RiskScope.USER,
                TOKEN,
                fixture.httpIpDigest());
        PreAuthAccess access = access(
                fixture,
                PreAuthWebRtcPhase.FAILED,
                21L,
                null,
                PreAuthWebRtcFailureReason.IP_MISMATCH,
                ciphertext);

        assertThat(fixture.service().inspect(access, HTTP_IP).outcome())
                .isEqualTo(WebRtcVerificationOutcome.IP_MISMATCH);
    }

    private static Fixture fixture() {
        return fixture(HTTP_IP);
    }

    private static Fixture fixture(String httpIp) {
        byte[] hmacKey = "abcdef0123456789abcdef0123456789"
                .getBytes(StandardCharsets.UTF_8);
        NetworkRiskIdentifier identifier = new NetworkRiskIdentifier(
                new HmacSha256Identifier(hmacKey));
        HmacIdentifier ipDigest = identifier.identifyIp(httpIp);
        String encryptionKey = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8));
        WebRtcIpProtector protector = new WebRtcIpProtector(
                encryptionKey,
                new ObjectMapper());
        PreAuthStore store = mock(PreAuthStore.class);
        NetworkRiskProperties properties = mock(NetworkRiskProperties.class);
        when(properties.webRtc()).thenReturn(new NetworkRiskProperties.WebRtc(
                Duration.ofSeconds(8),
                Duration.ofSeconds(12),
                Duration.ofSeconds(3),
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
        return new Fixture(service, store, protector, ipDigest);
    }

    private static void expectWritten(
            Fixture fixture,
            long generation,
            boolean matched,
            PreAuthWebRtcFailureReason failureReason) {
        when(fixture.store().writeWebRtcResult(
                eq(RiskScope.USER),
                eq(TOKEN),
                eq(DEVICE),
                eq(fixture.httpIpDigest()),
                eq(generation),
                eq(matched),
                eq(failureReason),
                any(String.class),
                eq(true),
                eq(Duration.ofMinutes(30))))
                .thenReturn(PreAuthWebRtcWriteResult.UPDATED);
    }

    private static PreAuthAccess access(
            Fixture fixture,
            PreAuthWebRtcPhase phase,
            long generation,
            Instant deadline,
            PreAuthWebRtcFailureReason failureReason,
            String ciphertext) {
        PreAuthState state = mock(PreAuthState.class);
        when(state.scope()).thenReturn(RiskScope.USER);
        when(state.deviceDigest()).thenReturn(DEVICE);
        when(state.currentIpDigest()).thenReturn(fixture.httpIpDigest());
        when(state.webRtcPhase()).thenReturn(phase);
        when(state.webRtcGeneration()).thenReturn(generation);
        when(state.webRtcDeadlineAt()).thenReturn(deadline);
        when(state.webRtcFailureReason()).thenReturn(failureReason);
        when(state.webRtcIps()).thenReturn(ciphertext);
        when(state.authenticated()).thenReturn(false);
        return new PreAuthAccess(TOKEN, state);
    }

    private static HmacIdentifier digest(char value) {
        return HmacIdentifier.fromProtectedValue(String.valueOf(value).repeat(43));
    }

    private record Fixture(
            WebRtcVerificationServiceImpl service,
            PreAuthStore store,
            WebRtcIpProtector protector,
            HmacIdentifier httpIpDigest) {
    }
}
