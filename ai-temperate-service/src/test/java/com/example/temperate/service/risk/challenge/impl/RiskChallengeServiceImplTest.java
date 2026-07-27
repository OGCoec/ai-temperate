package com.example.temperate.service.risk.challenge.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.risk.challenge.RiskChallengeIssue;
import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.ipintel.domain.NetworkType;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthChallengeActivation;
import com.example.temperate.service.risk.preauth.domain.PreAuthGeoSource;
import com.example.temperate.service.risk.preauth.domain.PreAuthRiskSource;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 验证 Challenge 引用只依赖单 Hash 活动 Nonce，并支持相同上下文稳定复用和一次消费。
 */
class RiskChallengeServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final String NONCE =
            "nonce-value-012345678901234567890123456789";
    private static final NetworkRiskIdentifier IDENTIFIER =
            new NetworkRiskIdentifier(new HmacSha256Identifier(
                    "risk-challenge-test-secret-0123456789".getBytes()));

    @Test
    void issueDerivesUrlSafeReferenceFromHashActivation() {
        PreAuthService preAuthService = mock(PreAuthService.class);
        when(preAuthService.activateChallenge(
                any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new PreAuthChallengeActivation(
                        NONCE,
                        NOW.plus(Duration.ofMinutes(5)),
                        true)));
        PreAuthAccess access = access(null, null, null);
        HmacIdentifier currentIp = IDENTIFIER.identifyIp("203.0.113.20");
        HmacIdentifier context =
                IDENTIFIER.identifyDecisionContext("decision-context");

        RiskChallengeIssue issue = service(preAuthService).issue(
                access,
                new RiskAssessment(
                        RiskDecision.CHALLENGE,
                        40,
                        true,
                        1,
                        currentIp,
                        context));

        assertThat(issue.reference()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(issue.reference()).isEqualTo(
                IDENTIFIER.deriveChallengeReference(
                        RiskScope.USER,
                        access.tokenDigest(),
                        context,
                        NONCE));
    }

    @Test
    void consumeChecksDerivedReferenceBeforeAtomicStoreTransition() {
        TrustedNetworkObservation observation = observation();
        HmacIdentifier currentIp =
                IDENTIFIER.identifyIp(observation.clientIp());
        PreAuthAccess provisional = access(null, null, null);
        HmacIdentifier context = IDENTIFIER.identifyDecisionContext(
                RiskScope.USER.name()
                        + "|"
                        + provisional.tokenDigest().value()
                        + "|"
                        + provisional.state().deviceDigest().value()
                        + "|"
                        + currentIp.value());
        PreAuthAccess access = access(NONCE, currentIp, context);
        String reference = IDENTIFIER.deriveChallengeReference(
                RiskScope.USER,
                access.tokenDigest(),
                context,
                NONCE);
        PreAuthService preAuthService = mock(PreAuthService.class);
        when(preAuthService.consumeChallengeAndTrust(
                any(), any(), any(), any(), any())).thenReturn(true);

        assertThat(service(preAuthService).consumeAndTrust(
                access,
                reference,
                observation)).isTrue();
        verify(preAuthService).consumeChallengeAndTrust(
                access,
                currentIp,
                context,
                NONCE,
                NOW);
    }

    @Test
    void forgedReferenceNeverReachesStoreConsumption() {
        TrustedNetworkObservation observation = observation();
        HmacIdentifier currentIp =
                IDENTIFIER.identifyIp(observation.clientIp());
        PreAuthAccess provisional = access(null, null, null);
        HmacIdentifier context = IDENTIFIER.identifyDecisionContext(
                RiskScope.USER.name()
                        + "|"
                        + provisional.tokenDigest().value()
                        + "|"
                        + provisional.state().deviceDigest().value()
                        + "|"
                        + currentIp.value());
        PreAuthService preAuthService = mock(PreAuthService.class);

        assertThat(service(preAuthService).consumeAndTrust(
                access(NONCE, currentIp, context),
                "forged-reference",
                observation)).isFalse();
        verify(preAuthService, never()).consumeChallengeAndTrust(
                any(), any(), any(), any(), any());
    }

    private static RiskChallengeServiceImpl service(
            PreAuthService preAuthService) {
        return new RiskChallengeServiceImpl(
                preAuthService,
                IDENTIFIER,
                new NetworkRiskMetrics(new SimpleMeterRegistry()));
    }

    private static PreAuthAccess access(
            String nonce,
            HmacIdentifier activeIp,
            HmacIdentifier activeContext) {
        HmacIdentifier token =
                IDENTIFIER.identifyPreAuthToken("preauth-token-value");
        HmacIdentifier device =
                IDENTIFIER.identifyDevice("device-installation-value");
        HmacIdentifier trustedIp =
                IDENTIFIER.identifyIp("198.51.100.10");
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
                        activeIp == null ? trustedIp : activeIp,
                        70,
                        "US",
                        64500L,
                        new BigDecimal("41.8781"),
                        new BigDecimal("-87.6298"),
                        NetworkType.RESIDENTIAL,
                        true,
                        PreAuthRiskSource.IP2LOCATION,
                        PreAuthGeoSource.CLOUDFLARE_EDGE,
                        trustedIp,
                        "US",
                        64500L,
                        new BigDecimal("41.8781"),
                        new BigDecimal("-87.6298"),
                        NOW.minus(Duration.ofHours(1)),
                        RiskDecision.CHALLENGE,
                        NOW,
                        activeContext,
                        null,
                        null,
                        1,
                        "[]",
                        1,
                        0,
                        nonce,
                        activeIp,
                        activeContext,
                        nonce == null ? null : NOW.plus(Duration.ofMinutes(5))));
    }

    private static TrustedNetworkObservation observation() {
        return new TrustedNetworkObservation(
                "203.0.113.20",
                "GB",
                64501L,
                new BigDecimal("51.5074"),
                new BigDecimal("-0.1278"),
                NOW);
    }
}
