package com.example.temperate.service.risk.decision.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.decision.NetworkRiskAssessmentService;
import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.decision.NetworkRiskScorePolicy;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.ipintel.service.IpIntelligenceService;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthNetworkSnapshot;
import com.example.temperate.service.risk.preauth.domain.PreAuthRequiredException;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.service.PreAuthNetworkSnapshotFactory;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 实现同 IP Redis 命中快路、基础信用硬阻断、不可能旅行固定扣分和短时多事件固定扣分。
 *
 * <p>30 分与 20 分都是布尔惩罚，绝不按事件次数累加；只有 ALLOW 才把当前快照提升为可信基线，
 * CHALLENGE 和 BLOCK 只保存当前评估，避免攻击流量污染可信位置。</p>
 */
@Service
public final class NetworkRiskAssessmentServiceImpl
        implements NetworkRiskAssessmentService {

    private static final double EARTH_RADIUS_KM = 6371.0088D;
    private static final double IMPOSSIBLE_SPEED_METERS_PER_SECOND = 340D;

    private final NetworkRiskIdentifier identifier;
    private final IpIntelligenceService ipIntelligenceService;
    private final PreAuthNetworkSnapshotFactory snapshotFactory;
    private final PreAuthService preAuthService;
    private final NetworkRiskProperties properties;
    private final NetworkRiskMetrics metrics;

    public NetworkRiskAssessmentServiceImpl(
            NetworkRiskIdentifier identifier,
            IpIntelligenceService ipIntelligenceService,
            PreAuthNetworkSnapshotFactory snapshotFactory,
            PreAuthService preAuthService,
            NetworkRiskProperties properties,
            NetworkRiskMetrics metrics) {
        this.identifier = Objects.requireNonNull(identifier);
        this.ipIntelligenceService = Objects.requireNonNull(ipIntelligenceService);
        this.snapshotFactory = Objects.requireNonNull(snapshotFactory);
        this.preAuthService = Objects.requireNonNull(preAuthService);
        this.properties = Objects.requireNonNull(properties);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public Mono<RiskAssessment> assess(
            PreAuthAccess access,
            TrustedNetworkObservation current) {
        HmacIdentifier currentIpDigest =
                identifier.identifyIp(current.clientIp());
        HmacIdentifier contextDigest =
                decisionContext(access, currentIpDigest);
        PreAuthState state = access.state();

        if (state.temporaryBlockUntil() != null
                && state.temporaryBlockUntil().isAfter(current.observedAt())) {
            if (!preAuthService.touch(access, current.observedAt())) {
                throw new PreAuthRequiredException();
            }
            metrics.decision(
                    state.scope(),
                    RiskDecision.BLOCK,
                    "temporary_block",
                    null);
            return Mono.just(new RiskAssessment(
                    RiskDecision.BLOCK,
                    state.currentTrustScore(),
                    false,
                    state.impossibleTravelCount(),
                    currentIpDigest,
                    contextDigest));
        }
        boolean sameTrustedIp = state.lastTrustedIpDigest() != null
                && currentIpDigest.equals(state.lastTrustedIpDigest());
        return ipIntelligenceService.lookup(current.clientIp())
                .map(lookup -> {
                    boolean sameCurrentIp = currentIpDigest.equals(
                            state.currentIpDigest());
                    if (lookup.initialCacheHit()
                            && sameCurrentIp
                            && sameTrustedIp
                            && state.lastDecision() == RiskDecision.ALLOW) {
                        return reuseStoredDecision(
                                access,
                                current,
                                currentIpDigest,
                                contextDigest,
                                RiskDecision.ALLOW,
                                "same_ip_cached_allow");
                    }
                    if (lookup.initialCacheHit()
                            && sameCurrentIp
                            && reusableActiveChallenge(
                                    state,
                                    currentIpDigest,
                                    contextDigest,
                                    current.observedAt())) {
                        return reuseStoredDecision(
                                access,
                                current,
                                currentIpDigest,
                                contextDigest,
                                RiskDecision.CHALLENGE,
                                "same_ip_active_challenge");
                    }
                    return evaluate(
                            access,
                            current,
                            currentIpDigest,
                            contextDigest,
                            snapshotFactory.merge(
                                    current,
                                    lookup.snapshot()),
                            !sameTrustedIp);
                });
    }

    private RiskAssessment reuseStoredDecision(
            PreAuthAccess access,
            TrustedNetworkObservation current,
            HmacIdentifier currentIpDigest,
            HmacIdentifier contextDigest,
            RiskDecision decision,
            String reason) {
        if (!preAuthService.touch(access, current.observedAt())) {
            throw new PreAuthRequiredException();
        }
        metrics.decision(access.state().scope(), decision, reason, null);
        return new RiskAssessment(
                decision,
                access.state().currentTrustScore(),
                false,
                access.state().impossibleTravelCount(),
                currentIpDigest,
                contextDigest);
    }

    private static boolean reusableActiveChallenge(
            PreAuthState state,
            HmacIdentifier currentIpDigest,
            HmacIdentifier contextDigest,
            java.time.Instant now) {
        return state.lastDecision() == RiskDecision.CHALLENGE
                && state.activeChallengeNonce() != null
                && !state.activeChallengeNonce().isBlank()
                && currentIpDigest.equals(state.activeChallengeIpDigest())
                && contextDigest.equals(state.activeChallengeContextDigest())
                && state.activeChallengeExpiresAt() != null
                && state.activeChallengeExpiresAt().isAfter(now);
    }

    private RiskAssessment evaluate(
            PreAuthAccess access,
            TrustedNetworkObservation current,
            HmacIdentifier currentIpDigest,
            HmacIdentifier contextDigest,
            PreAuthNetworkSnapshot snapshot,
            boolean ipChanged) {
        int baseScore = snapshot.trustScore();
        boolean impossibleTravel = ipChanged
                && impossibleTravel(access.state(), current, snapshot);
        long eventCount = 0L;
        if (ipChanged) {
            HmacIdentifier eventDigest = impossibleTravel
                    ? identifier.identifyTravelEvent(
                    access.state().scope().name()
                            + "|"
                            + access.state().deviceDigest().value()
                            + "|"
                            + access.state().lastTrustedIpDigest().value()
                            + "|"
                            + currentIpDigest.value())
                    : null;
            eventCount = preAuthService.recordImpossibleTravelEvent(
                    access,
                    eventDigest,
                    current.observedAt());
        }

        int finalScore = baseScore
                - (impossibleTravel ? 30 : 0)
                - (eventCount > 5 ? 20 : 0);
        RiskDecision decision =
                NetworkRiskScorePolicy.decide(baseScore, finalScore);
        if (!preAuthService.recordAssessment(
                access,
                snapshot,
                decision,
                current.observedAt(),
                contextDigest,
                decision == RiskDecision.BLOCK
                        ? current.observedAt().plus(properties.temporaryBlockTtl())
                        : null,
                decision == RiskDecision.ALLOW)) {
            throw new PreAuthRequiredException();
        }
        metrics.decision(
                access.state().scope(),
                decision,
                impossibleTravel
                        ? "ip_change_impossible_travel"
                        : ipChanged ? "ip_change" : "same_ip_score_refresh",
                null);
        return new RiskAssessment(
                decision,
                Math.max(0, Math.min(100, finalScore)),
                impossibleTravel,
                eventCount,
                currentIpDigest,
                contextDigest);
    }

    private boolean impossibleTravel(
            PreAuthState previous,
            TrustedNetworkObservation current,
            PreAuthNetworkSnapshot snapshot) {
        if (previous.lastTrustedIpDigest() == null
                || previous.lastTrustedLatitude() == null
                || previous.lastTrustedLongitude() == null
                || snapshot.latitude() == null
                || snapshot.longitude() == null
                || previous.lastTrustedObservedAt() == null) {
            return false;
        }
        Duration elapsed = Duration.between(
                previous.lastTrustedObservedAt(),
                current.observedAt());
        if (elapsed.isZero()
                || elapsed.isNegative()
                || elapsed.compareTo(properties.impossibleTravelWindow()) > 0) {
            return false;
        }
        double distanceKm = distanceKm(
                previous.lastTrustedLatitude(),
                previous.lastTrustedLongitude(),
                snapshot.latitude(),
                snapshot.longitude());
        return isImpossibleTravel(
                distanceKm,
                elapsed,
                properties.impossibleTravelWindow(),
                properties.impossibleTravelMinimumDistanceKm());
    }

    /**
     * 按距离、耗时和允许窗口判断一次位移是否达到不可能旅行阈值。
     */
    static boolean isImpossibleTravel(
            double distanceKm,
            Duration elapsed,
            Duration maximumWindow,
            double minimumDistanceKm) {
        if (distanceKm < minimumDistanceKm
                || elapsed.isZero()
                || elapsed.isNegative()
                || elapsed.compareTo(maximumWindow) > 0) {
            return false;
        }
        double elapsedSeconds = elapsed.toNanos() / 1_000_000_000D;
        double speedMetersPerSecond =
                distanceKm * 1000D / elapsedSeconds;
        return speedMetersPerSecond >= IMPOSSIBLE_SPEED_METERS_PER_SECOND;
    }

    private HmacIdentifier decisionContext(
            PreAuthAccess access,
            HmacIdentifier currentIpDigest) {
        return identifier.identifyDecisionContext(
                access.state().scope().name()
                        + "|"
                        + access.tokenDigest().value()
                        + "|"
                        + access.state().deviceDigest().value()
                        + "|"
                        + currentIpDigest.value());
    }

    private static double distanceKm(
            BigDecimal fromLatitude,
            BigDecimal fromLongitude,
            BigDecimal toLatitude,
            BigDecimal toLongitude) {
        double lat1 = Math.toRadians(fromLatitude.doubleValue());
        double lat2 = Math.toRadians(toLatitude.doubleValue());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(
                toLongitude.subtract(fromLongitude)
                        .setScale(8, RoundingMode.HALF_UP)
                        .doubleValue());
        double haversine = Math.sin(deltaLat / 2D) * Math.sin(deltaLat / 2D)
                + Math.cos(lat1)
                * Math.cos(lat2)
                * Math.sin(deltaLon / 2D)
                * Math.sin(deltaLon / 2D);
        double boundedHaversine =
                Math.max(0D, Math.min(1D, haversine));
        return EARTH_RADIUS_KM
                * 2D
                * Math.atan2(
                        Math.sqrt(boundedHaversine),
                        Math.sqrt(1D - boundedHaversine));
    }
}
