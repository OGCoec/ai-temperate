package com.example.temperate.service.risk.preauth.service.impl;

import com.example.temperate.service.risk.challenge.RiskChallengeIssue;
import com.example.temperate.service.risk.challenge.RiskChallengeService;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.decision.NetworkRiskAssessmentService;
import com.example.temperate.service.risk.decision.NetworkRiskScorePolicy;
import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.ipintel.service.IpIntelligenceService;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthBootstrapOutcome;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.domain.PreAuthNetworkSnapshot;
import com.example.temperate.service.risk.preauth.service.PreAuthNetworkSnapshotFactory;
import com.example.temperate.service.risk.preauth.service.PreAuthRiskBootstrapService;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 在首次 Bootstrap 时执行共享 IP 情报降级链，并把完整信用快照写入 PreAuth v4 单 Hash。
 *
 * <p>首次请求不计算不可能旅行；基础分低于 40 阻断、40 至 59 挑战、60 及以上允许。已有状态
 * 统一交给评估服务依据 Redis 初始命中事实复用或重评估。</p>
 */
@Service
public final class PreAuthRiskBootstrapServiceImpl
        implements PreAuthRiskBootstrapService {

    private final PreAuthService preAuthService;
    private final IpIntelligenceService ipIntelligenceService;
    private final PreAuthNetworkSnapshotFactory snapshotFactory;
    private final NetworkRiskAssessmentService assessmentService;
    private final RiskChallengeService challengeService;
    private final NetworkRiskProperties properties;

    public PreAuthRiskBootstrapServiceImpl(
            PreAuthService preAuthService,
            IpIntelligenceService ipIntelligenceService,
            PreAuthNetworkSnapshotFactory snapshotFactory,
            NetworkRiskAssessmentService assessmentService,
            RiskChallengeService challengeService,
            NetworkRiskProperties properties) {
        this.preAuthService = Objects.requireNonNull(preAuthService);
        this.ipIntelligenceService = Objects.requireNonNull(ipIntelligenceService);
        this.snapshotFactory = Objects.requireNonNull(snapshotFactory);
        this.assessmentService = Objects.requireNonNull(assessmentService);
        this.challengeService = Objects.requireNonNull(challengeService);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public Mono<PreAuthBootstrapOutcome> bootstrap(
            RiskScope scope,
            String existingRawToken,
            String rawDeviceId,
            TrustedNetworkObservation observation,
            boolean resetExisting,
            boolean browserChallengeSupported) {
        boolean suppliedExisting =
                existingRawToken != null && !existingRawToken.isBlank();
        Optional<PreAuthAccess> existing = preAuthService.resolve(
                scope,
                existingRawToken,
                rawDeviceId);
        if (resetExisting) {
            existing.ifPresent(access ->
                    preAuthService.revoke(scope, existingRawToken));
            existing = Optional.empty();
        }
        boolean reauthenticationRequired =
                suppliedExisting && (resetExisting || existing.isEmpty());
        if (existing.isPresent()) {
            PreAuthAccess access = existing.orElseThrow();
            return assessmentService.assess(access, observation)
                    .map(assessment -> outcome(
                            existingRawToken,
                            access,
                            assessment,
                            reauthenticationRequired,
                            browserChallengeSupported,
                            observation.observedAt()));
        }
        return ipIntelligenceService.lookup(observation.clientIp())
                .map(lookup -> snapshotFactory.merge(
                        observation,
                        lookup.snapshot()))
                .map(snapshot -> createInitial(
                        scope,
                        rawDeviceId,
                        snapshot,
                        reauthenticationRequired,
                        browserChallengeSupported));
    }

    private PreAuthBootstrapOutcome createInitial(
            RiskScope scope,
            String rawDeviceId,
            PreAuthNetworkSnapshot snapshot,
            boolean reauthenticationRequired,
            boolean browserChallengeSupported) {
        RiskDecision decision =
                NetworkRiskScorePolicy.decide(
                        snapshot.trustScore(),
                        snapshot.trustScore());
        PreAuthIssue issue = preAuthService.createEvaluated(
                scope,
                rawDeviceId,
                snapshot,
                decision,
                decision == RiskDecision.BLOCK
                        ? snapshot.observedAt()
                                .plus(properties.anonymousPreAuthTtl())
                        : null,
                decision == RiskDecision.ALLOW);
        PreAuthAccess access = preAuthService.resolve(
                        scope,
                        issue.rawToken(),
                        rawDeviceId)
                .orElseThrow(() -> new IllegalStateException(
                        "Created PreAuth state is unavailable."));
        RiskAssessment assessment = new RiskAssessment(
                decision,
                snapshot.trustScore(),
                false,
                0L,
                snapshot.ipDigest(),
                access.state().lastDecisionContextDigest());
        return outcome(
                issue,
                access,
                assessment,
                reauthenticationRequired,
                browserChallengeSupported);
    }

    private PreAuthBootstrapOutcome outcome(
            String rawToken,
            PreAuthAccess access,
            RiskAssessment assessment,
            boolean reauthenticationRequired,
            boolean browserChallengeSupported,
            Instant now) {
        Duration ttl = access.state().authenticated()
                ? properties.authenticatedPreAuthTtl()
                : properties.anonymousPreAuthTtl();
        return outcome(
                new PreAuthIssue(rawToken, now.plus(ttl)),
                access,
                assessment,
                reauthenticationRequired,
                browserChallengeSupported);
    }

    private PreAuthBootstrapOutcome outcome(
            PreAuthIssue issue,
            PreAuthAccess access,
            RiskAssessment assessment,
            boolean reauthenticationRequired,
            boolean browserChallengeSupported) {
        RiskChallengeIssue challenge = null;
        if (properties.mode() == NetworkRiskMode.ENFORCE
                && assessment.decision() == RiskDecision.CHALLENGE
                && browserChallengeSupported) {
            challenge = challengeService.issue(access, assessment);
        }
        return new PreAuthBootstrapOutcome(
                issue,
                access,
                assessment,
                challenge,
                reauthenticationRequired);
    }

}
