package com.example.temperate.service.risk.preauth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.risk.challenge.RiskChallengeService;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.decision.NetworkRiskAssessmentService;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSnapshot;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceLookupResult;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSource;
import com.example.temperate.service.risk.ipintel.domain.NetworkType;
import com.example.temperate.service.risk.ipintel.service.IpIntelligenceService;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthBootstrapOutcome;
import com.example.temperate.service.risk.preauth.domain.PreAuthGeoSource;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.domain.PreAuthRiskSource;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.service.PreAuthNetworkSnapshotFactory;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 验证首次 PreAuth 必须查询 IP 信用，并锁定 39/40/59/60 四个首次决策边界。
 */
class PreAuthRiskBootstrapServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final String DEVICE = "device-installation-test-value";
    private static final String RAW_TOKEN =
            "preauth-token-012345678901234567890123456";
    private static final String LEGACY_RAW_TOKEN =
            "legacy-preauth-01234567890123456789012345";
    private static final NetworkRiskIdentifier IDENTIFIER =
            new NetworkRiskIdentifier(new HmacSha256Identifier(
                    "preauth-bootstrap-test-secret-012345".getBytes()));

    @Test
    void firstScoresUseApprovedDecisionBoundaries() {
        assertThat(bootstrap(39).assessment().decision())
                .isEqualTo(RiskDecision.BLOCK);
        assertThat(bootstrap(40).assessment().decision())
                .isEqualTo(RiskDecision.CHALLENGE);
        assertThat(bootstrap(59).assessment().decision())
                .isEqualTo(RiskDecision.CHALLENGE);
        assertThat(bootstrap(60).assessment().decision())
                .isEqualTo(RiskDecision.ALLOW);
    }

    @Test
    void firstBlockCreatesV4StateWithoutTrustedBaseline() {
        Fixture fixture = fixture(39);

        PreAuthBootstrapOutcome outcome = fixture.service().bootstrap(
                RiskScope.USER,
                null,
                DEVICE,
                observation(),
                false,
                false).block();

        assertThat(outcome).isNotNull();
        assertThat(outcome.access().state().lastTrustedIpDigest()).isNull();
        verify(fixture.preAuthService()).createEvaluated(
                eq(RiskScope.USER),
                eq(DEVICE),
                any(),
                eq(RiskDecision.BLOCK),
                eq(NOW.plus(Duration.ofMinutes(30))),
                eq(false));
    }

    @Test
    void firstAllowWritesTrustedBaseline() {
        Fixture fixture = fixture(60);

        fixture.service().bootstrap(
                RiskScope.USER,
                null,
                DEVICE,
                observation(),
                false,
                false).block();

        verify(fixture.preAuthService()).createEvaluated(
                eq(RiskScope.USER),
                eq(DEVICE),
                any(),
                eq(RiskDecision.ALLOW),
                isNull(),
                eq(true));
    }

    @Test
    void firstChallengePersistsCurrentSnapshotWithoutTrustedBaseline() {
        Fixture fixture = fixture(40);

        PreAuthBootstrapOutcome outcome = fixture.service().bootstrap(
                RiskScope.USER,
                null,
                DEVICE,
                observation(),
                false,
                true).block();

        assertThat(outcome).isNotNull();
        assertThat(outcome.access().state().lastTrustedIpDigest()).isNull();
        verify(fixture.preAuthService()).createEvaluated(
                eq(RiskScope.USER),
                eq(DEVICE),
                any(),
                eq(RiskDecision.CHALLENGE),
                isNull(),
                eq(false));
    }

    @Test
    void legacyNamespaceMissCreatesV4StateAndRequiresReauthentication() {
        Fixture fixture = fixture(60);
        when(fixture.preAuthService().resolve(
                RiskScope.USER,
                LEGACY_RAW_TOKEN,
                DEVICE)).thenReturn(Optional.empty());

        PreAuthBootstrapOutcome outcome = fixture.service().bootstrap(
                RiskScope.USER,
                LEGACY_RAW_TOKEN,
                DEVICE,
                observation(),
                false,
                false).block();

        assertThat(outcome).isNotNull();
        assertThat(outcome.reauthenticationRequired()).isTrue();
        assertThat(outcome.issue().rawToken()).isEqualTo(RAW_TOKEN);
    }

    private static PreAuthBootstrapOutcome bootstrap(int score) {
        return fixture(score).service().bootstrap(
                RiskScope.USER,
                null,
                DEVICE,
                observation(),
                false,
                false).block();
    }

    private static Fixture fixture(int score) {
        PreAuthService preAuthService = mock(PreAuthService.class);
        IpIntelligenceService intelligenceService =
                mock(IpIntelligenceService.class);
        NetworkRiskProperties properties = mock(NetworkRiskProperties.class);
        when(properties.mode()).thenReturn(NetworkRiskMode.ENFORCE);
        when(properties.temporaryBlockTtl())
                .thenReturn(Duration.ofMinutes(10));
        when(properties.positiveCacheTtl()).thenReturn(Duration.ofHours(6));
        when(properties.anonymousPreAuthTtl())
                .thenReturn(Duration.ofMinutes(30));
        when(intelligenceService.lookup(any()))
                .thenReturn(Mono.just(new IpIntelligenceLookupResult(
                        intelligence(score),
                        false)));
        when(preAuthService.createEvaluated(
                any(), any(), any(), any(), nullable(Instant.class), anyBoolean()))
                .thenReturn(new PreAuthIssue(
                        RAW_TOKEN,
                        NOW.plus(Duration.ofMinutes(30))));
        PreAuthAccess access = access(score);
        when(preAuthService.resolve(
                RiskScope.USER,
                null,
                DEVICE)).thenReturn(Optional.empty());
        when(preAuthService.resolve(
                RiskScope.USER,
                RAW_TOKEN,
                DEVICE)).thenReturn(Optional.of(access));
        PreAuthNetworkSnapshotFactory factory =
                new PreAuthNetworkSnapshotFactoryImpl(IDENTIFIER);
        PreAuthRiskBootstrapServiceImpl service =
                new PreAuthRiskBootstrapServiceImpl(
                        preAuthService,
                        intelligenceService,
                        factory,
                        mock(NetworkRiskAssessmentService.class),
                        mock(RiskChallengeService.class),
                        properties);
        return new Fixture(service, preAuthService);
    }

    private static PreAuthAccess access(int score) {
        HmacIdentifier token = IDENTIFIER.identifyPreAuthToken(RAW_TOKEN);
        HmacIdentifier device = IDENTIFIER.identifyDevice(DEVICE);
        HmacIdentifier ip = IDENTIFIER.identifyIp("198.51.100.10");
        RiskDecision decision = score < 40
                ? RiskDecision.BLOCK
                : score < 60
                        ? RiskDecision.CHALLENGE
                        : RiskDecision.ALLOW;
        HmacIdentifier context = IDENTIFIER.identifyDecisionContext(
                RiskScope.USER.name()
                        + "|"
                        + token.value()
                        + "|"
                        + device.value()
                        + "|"
                        + ip.value());
        boolean trusted = decision == RiskDecision.ALLOW;
        return new PreAuthAccess(
                token,
                new PreAuthState(
                        PreAuthState.CURRENT_SCHEMA_VERSION,
                        RiskScope.USER,
                        "ANONYMOUS",
                        RiskSessionType.NONE,
                        null,
                        device,
                        NOW,
                        ip,
                        score,
                        "US",
                        8075L,
                        new BigDecimal("41.85003"),
                        new BigDecimal("-87.65005"),
                        NetworkType.RESIDENTIAL,
                        true,
                        PreAuthRiskSource.IP2LOCATION,
                        PreAuthGeoSource.CLOUDFLARE_EDGE,
                        trusted ? ip : null,
                        trusted ? "US" : null,
                        trusted ? 8075L : null,
                        trusted ? new BigDecimal("41.85003") : null,
                        trusted ? new BigDecimal("-87.65005") : null,
                        trusted ? NOW : null,
                        decision,
                        NOW,
                        context,
                        decision == RiskDecision.BLOCK
                                ? NOW.plus(Duration.ofMinutes(10))
                                : null,
                        null,
                        0,
                        "[]",
                        0,
                        0,
                        null,
                        null,
                        null,
                        null));
    }

    private static TrustedNetworkObservation observation() {
        return new TrustedNetworkObservation(
                "198.51.100.10",
                "US",
                8075L,
                new BigDecimal("41.85003"),
                new BigDecimal("-87.65005"),
                NOW);
    }

    private static IpIntelligenceSnapshot intelligence(int score) {
        return new IpIntelligenceSnapshot(
                IpIntelligenceSnapshot.CURRENT_SCHEMA_VERSION,
                score,
                "US",
                8075L,
                new BigDecimal("41.85003"),
                new BigDecimal("-87.65005"),
                NetworkType.RESIDENTIAL,
                true,
                IpIntelligenceSource.IP2LOCATION);
    }

    private record Fixture(
            PreAuthRiskBootstrapServiceImpl service,
            PreAuthService preAuthService) {
    }
}
