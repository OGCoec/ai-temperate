package com.example.temperate.service.risk.challenge.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.challenge.RiskChallengeIssue;
import com.example.temperate.service.risk.challenge.RiskChallengeService;
import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthChallengeActivation;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 在 PreAuth v6 单 Hash 内签发或复用活动 Challenge，并在验证成功后原子推进可信网络。
 *
 * <p>客户端引用由服务器 HMAC 从 PreAuth、决策上下文和随机 Nonce 派生；Redis 不保存原始引用，
 * 重复请求相同上下文不会增加签发计数，成功引用只能消费一次。</p>
 */
@Service
public final class RiskChallengeServiceImpl implements RiskChallengeService {

    private static final int NONCE_BYTES = 32;

    private final PreAuthService preAuthService;
    private final NetworkRiskIdentifier identifier;
    private final NetworkRiskMetrics metrics;
    private final SecureRandom secureRandom = new SecureRandom();

    public RiskChallengeServiceImpl(
            PreAuthService preAuthService,
            NetworkRiskIdentifier identifier,
            NetworkRiskMetrics metrics) {
        this.preAuthService = Objects.requireNonNull(preAuthService);
        this.identifier = Objects.requireNonNull(identifier);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public RiskChallengeIssue issue(
            PreAuthAccess access,
            RiskAssessment assessment) {
        String proposedNonce = randomNonce();
        Instant now = Instant.now();
        PreAuthChallengeActivation activation = preAuthService.activateChallenge(
                        access,
                        assessment.currentIpDigest(),
                        assessment.decisionContextDigest(),
                        proposedNonce,
                        now)
                .orElseThrow(() -> new IllegalStateException(
                        "Risk challenge activation failed."));
        String reference = identifier.deriveChallengeReference(
                access.state().scope(),
                access.tokenDigest(),
                assessment.decisionContextDigest(),
                activation.nonce());
        metrics.challenge(
                access.state().scope(),
                activation.newlyIssued() ? "issued" : "reused");
        return new RiskChallengeIssue(reference, activation.expiresAt());
    }

    @Override
    public boolean consumeAndTrust(
            PreAuthAccess access,
            String rawReference,
            TrustedNetworkObservation current) {
        if (rawReference == null || rawReference.isBlank()) {
            return false;
        }
        HmacIdentifier currentIpDigest =
                identifier.identifyIp(current.clientIp());
        HmacIdentifier contextDigest = identifier.identifyDecisionContext(
                access.state().scope().name()
                        + "|"
                        + access.tokenDigest().value()
                        + "|"
                        + access.state().deviceDigest().value()
                        + "|"
                        + currentIpDigest.value());
        String nonce = access.state().activeChallengeNonce();
        if (nonce == null
                || access.state().activeChallengeContextDigest() == null
                || !access.state().activeChallengeContextDigest().equals(contextDigest)
                || access.state().activeChallengeIpDigest() == null
                || !access.state().activeChallengeIpDigest().equals(currentIpDigest)) {
            metrics.challenge(access.state().scope(), "rejected");
            return false;
        }
        String expected = identifier.deriveChallengeReference(
                access.state().scope(),
                access.tokenDigest(),
                contextDigest,
                nonce);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                rawReference.trim().getBytes(StandardCharsets.US_ASCII))) {
            metrics.challenge(access.state().scope(), "rejected");
            return false;
        }
        boolean consumed = preAuthService.consumeChallengeAndTrust(
                access,
                currentIpDigest,
                contextDigest,
                nonce,
                current.observedAt());
        metrics.challenge(
                access.state().scope(),
                consumed ? "consumed" : "rejected");
        return consumed;
    }

    private String randomNonce() {
        byte[] value = new byte[NONCE_BYTES];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
