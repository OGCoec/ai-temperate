package com.example.temperate.service.risk.decision.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSnapshot;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceLookupResult;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSource;
import com.example.temperate.service.risk.ipintel.domain.NetworkType;
import com.example.temperate.service.risk.ipintel.service.IpIntelligenceService;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthGeoSource;
import com.example.temperate.service.risk.preauth.domain.PreAuthRiskSource;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.service.PreAuthNetworkSnapshotFactory;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.risk.preauth.service.impl.PreAuthNetworkSnapshotFactoryImpl;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 验证 Redis 命中决策收敛、缓存未命中重新评估及不可能旅行固定扣分规则。
 */
class NetworkRiskAssessmentServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final NetworkRiskIdentifier IDENTIFIER =
            new NetworkRiskIdentifier(new HmacSha256Identifier(
                    "network-risk-test-secret-0123456789abc".getBytes()));

    @Test
    void sameTrustedIpWithRedisHitReusesAllowDecision() {
        IpIntelligenceService intelligenceService =
                mock(IpIntelligenceService.class);
        when(intelligenceService.lookup(anyString()))
                .thenReturn(Mono.just(new IpIntelligenceLookupResult(
                        snapshot(90),
                        true)));
        PreAuthService preAuthService = mock(PreAuthService.class);
        when(preAuthService.touch(any(), any())).thenReturn(true);
        NetworkRiskAssessmentServiceImpl service =
                service(intelligenceService, preAuthService);

        RiskAssessment result = service.assess(
                access("198.51.100.10"),
                observation(
                        "198.51.100.10",
                        "US",
                        "41.8781",
                        "-87.6298",
                        NOW)).block();

        assertThat(result.decision()).isEqualTo(RiskDecision.ALLOW);
        verify(intelligenceService).lookup("198.51.100.10");
        verify(preAuthService, never()).recordAssessment(
                any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void sameTrustedIpWithRedisMissReevaluatesScore() {
        IpIntelligenceService intelligenceService =
                mock(IpIntelligenceService.class);
        when(intelligenceService.lookup(anyString()))
                .thenReturn(Mono.just(new IpIntelligenceLookupResult(
                        snapshot(40),
                        false)));
        PreAuthService preAuthService = mock(PreAuthService.class);
        when(preAuthService.recordAssessment(
                any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(true);

        RiskAssessment result = service(intelligenceService, preAuthService)
                .assess(
                        access("198.51.100.10"),
                        observation(
                                "198.51.100.10",
                                "US",
                                "41.8781",
                                "-87.6298",
                                NOW))
                .block();

        assertThat(result.decision()).isEqualTo(RiskDecision.CHALLENGE);
        verify(preAuthService).recordAssessment(
                any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void sameIpChallengeWithRedisHitReusesStoredDecision() {
        IpIntelligenceService intelligenceService =
                mock(IpIntelligenceService.class);
        when(intelligenceService.lookup(anyString()))
                .thenReturn(Mono.just(new IpIntelligenceLookupResult(
                        snapshot(58),
                        true)));
        PreAuthService preAuthService = mock(PreAuthService.class);
        when(preAuthService.touch(any(), any())).thenReturn(true);

        RiskAssessment result = service(intelligenceService, preAuthService)
                .assess(
                        access(
                                "198.51.100.10",
                                RiskDecision.CHALLENGE,
                                false),
                        observation(
                                "198.51.100.10",
                                "US",
                                "41.8781",
                                "-87.6298",
                                NOW))
                .block();

        assertThat(result.decision()).isEqualTo(RiskDecision.CHALLENGE);
        verify(preAuthService, never()).recordAssessment(
                any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void baseTrustBelowFortyBlocksAndFortyChallenges() {
        assertThat(assessChangedIp(39, 0).decision())
                .isEqualTo(RiskDecision.BLOCK);
        assertThat(assessChangedIpWithoutImpossibleTravel(40).decision())
                .isEqualTo(RiskDecision.CHALLENGE);
    }

    @Test
    void impossibleTravelPenaltyDoesNotAccumulateByCount() {
        RiskAssessment result = assessChangedIp(90, 5);

        assertThat(result.impossibleTravel()).isTrue();
        assertThat(result.finalScore()).isEqualTo(60);
        assertThat(result.decision()).isEqualTo(RiskDecision.ALLOW);
    }

    @Test
    void sixthDistinctEventAddsOneFixedTwentyPointPenalty() {
        RiskAssessment result = assessChangedIp(69, 6);

        assertThat(result.finalScore()).isEqualTo(19);
        assertThat(result.decision()).isEqualTo(RiskDecision.BLOCK);
    }

    @Test
    void distanceWindowAndSpeedUseApprovedBoundaries() {
        assertThat(NetworkRiskAssessmentServiceImpl.isImpossibleTravel(
                        199.999D,
                        Duration.ofSeconds(500),
                        Duration.ofHours(24),
                        200D))
                .isFalse();
        assertThat(NetworkRiskAssessmentServiceImpl.isImpossibleTravel(
                        200D,
                        Duration.ofSeconds(500),
                        Duration.ofHours(24),
                        200D))
                .isTrue();
        assertThat(NetworkRiskAssessmentServiceImpl.isImpossibleTravel(
                        1_224D,
                        Duration.ofHours(1),
                        Duration.ofHours(24),
                        200D))
                .isTrue();
        assertThat(NetworkRiskAssessmentServiceImpl.isImpossibleTravel(
                        12_240D,
                        Duration.ofHours(10),
                        Duration.ofHours(24),
                        200D))
                .isTrue();
        assertThat(NetworkRiskAssessmentServiceImpl.isImpossibleTravel(
                        29_376D,
                        Duration.ofHours(24),
                        Duration.ofHours(24),
                        200D))
                .isTrue();
        assertThat(NetworkRiskAssessmentServiceImpl.isImpossibleTravel(
                        29_376D,
                        Duration.ofHours(24).plusMillis(1),
                        Duration.ofHours(24),
                        200D))
                .isFalse();
    }

    private static RiskAssessment assessChangedIp(int trustScore, long count) {
        return assessChangedIp(
                trustScore,
                count,
                observation(
                        "203.0.113.20",
                        "GB",
                        "51.5074",
                        "-0.1278",
                        NOW));
    }

    private static RiskAssessment assessChangedIpWithoutImpossibleTravel(
            int trustScore) {
        return assessChangedIp(
                trustScore,
                0,
                observation(
                        "203.0.113.20",
                        "US",
                        "41.8781",
                        "-87.6298",
                        NOW));
    }

    private static RiskAssessment assessChangedIp(
            int trustScore,
            long count,
            TrustedNetworkObservation current) {
        IpIntelligenceService intelligenceService =
                mock(IpIntelligenceService.class);
        when(intelligenceService.lookup(anyString()))
                .thenReturn(Mono.just(new IpIntelligenceLookupResult(
                        snapshot(trustScore),
                        false)));
        PreAuthService preAuthService = mock(PreAuthService.class);
        when(preAuthService.recordImpossibleTravelEvent(
                any(), any(), any())).thenReturn(count);
        when(preAuthService.recordAssessment(
                any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(true);
        return service(intelligenceService, preAuthService)
                .assess(access("198.51.100.10"), current)
                .block();
    }

    private static NetworkRiskAssessmentServiceImpl service(
            IpIntelligenceService intelligenceService,
            PreAuthService preAuthService) {
        PreAuthNetworkSnapshotFactory snapshotFactory =
                new PreAuthNetworkSnapshotFactoryImpl(IDENTIFIER);
        return new NetworkRiskAssessmentServiceImpl(
                IDENTIFIER,
                intelligenceService,
                snapshotFactory,
                preAuthService,
                properties(),
                new NetworkRiskMetrics(new SimpleMeterRegistry()));
    }

    private static PreAuthAccess access(String trustedIp) {
        return access(trustedIp, RiskDecision.ALLOW, true);
    }

    private static PreAuthAccess access(
            String currentIp,
            RiskDecision decision,
            boolean trusted) {
        HmacIdentifier token = IDENTIFIER.identifyPreAuthToken(
                "preauth-test-token-012345678901234567890123");
        HmacIdentifier device = IDENTIFIER.identifyDevice(
                "device-test-01234567890123456789012345");
        HmacIdentifier ipDigest = IDENTIFIER.identifyIp(currentIp);
        HmacIdentifier context = IDENTIFIER.identifyDecisionContext(
                RiskScope.USER.name()
                        + "|"
                        + token.value()
                        + "|"
                        + device.value()
                        + "|"
                        + ipDigest.value());
        return new PreAuthAccess(
                token,
                new PreAuthState(
                        PreAuthState.CURRENT_SCHEMA_VERSION,
                        RiskScope.USER,
                        "ANONYMOUS",
                        RiskSessionType.NONE,
                        null,
                        device,
                        NOW.minus(Duration.ofMinutes(1)),
                        ipDigest,
                        90,
                        "US",
                        64500L,
                        new BigDecimal("41.8781"),
                        new BigDecimal("-87.6298"),
                        NetworkType.RESIDENTIAL,
                        true,
                        PreAuthRiskSource.IP2LOCATION,
                        PreAuthGeoSource.CLOUDFLARE_EDGE,
                        trusted ? ipDigest : null,
                        trusted ? "US" : null,
                        trusted ? 64500L : null,
                        trusted ? new BigDecimal("41.8781") : null,
                        trusted ? new BigDecimal("-87.6298") : null,
                        trusted ? NOW.minus(Duration.ofHours(4)) : null,
                        decision,
                        NOW.minus(Duration.ofHours(4)),
                        context,
                        null,
                        null,
                        0,
                        "[]",
                        decision == RiskDecision.CHALLENGE ? 1 : 0,
                        0,
                        decision == RiskDecision.CHALLENGE
                                ? "active-challenge-nonce"
                                : null,
                        decision == RiskDecision.CHALLENGE
                                ? ipDigest
                                : null,
                        decision == RiskDecision.CHALLENGE
                                ? context
                                : null,
                        decision == RiskDecision.CHALLENGE
                                ? NOW.plus(Duration.ofMinutes(5))
                                : null));
    }

    private static TrustedNetworkObservation observation(
            String ip,
            String country,
            String latitude,
            String longitude,
            Instant observedAt) {
        return new TrustedNetworkObservation(
                ip,
                country,
                64501L,
                new BigDecimal(latitude),
                new BigDecimal(longitude),
                observedAt);
    }

    private static IpIntelligenceSnapshot snapshot(int trustScore) {
        return new IpIntelligenceSnapshot(
                IpIntelligenceSnapshot.CURRENT_SCHEMA_VERSION,
                trustScore,
                "GB",
                64501L,
                new BigDecimal("51.5074"),
                new BigDecimal("-0.1278"),
                NetworkType.RESIDENTIAL,
                true,
                IpIntelligenceSource.IP2LOCATION);
    }

    private static NetworkRiskProperties properties() {
        String secret = Base64.getEncoder().encodeToString(
                "network-risk-properties-test-0123456789".getBytes());
        return new NetworkRiskProperties(
                NetworkRiskMode.ENFORCE,
                secret,
                secret,
                URI.create("https://api.ip2location.test/"),
                URI.create("https://api.iping.test/"),
                true,
                Duration.ofSeconds(8),
                Duration.ofHours(6),
                Duration.ofMinutes(10),
                Duration.ofSeconds(10),
                32,
                Duration.ofMinutes(30),
                Duration.ofHours(6),
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                200D,
                Duration.ofHours(24),
                Duration.ofMinutes(10),
                webRtc(secret));
    }

    private static NetworkRiskProperties.WebRtc webRtc(String secret) {
        return new NetworkRiskProperties.WebRtc(
                Duration.ofSeconds(8),
                Duration.ofSeconds(12),
                Duration.ofSeconds(3),
                List.of(
                        URI.create("stun:stun.l.google.com:19302"),
                        URI.create("stun:stun.cloudflare.com:3478"),
                        URI.create("stun:global.stun.twilio.com:3478"),
                        URI.create("stun:stun.nextcloud.com:3478")),
                8,
                secret);
    }
}
