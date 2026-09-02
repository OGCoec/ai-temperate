package com.example.temperate.service.risk.preauth.store;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthChallengeActivation;
import com.example.temperate.service.risk.preauth.domain.PreAuthNetworkSnapshot;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcBeginResult;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcFailureReason;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcWriteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 定义 PreAuth Hash 的创建、读取、滑动续期、决策记录、WebRTC 状态迁移和可信网络更新原子边界。
 */
public interface PreAuthStore {

    boolean create(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            PreAuthNetworkSnapshot snapshot,
            RiskDecision decision,
            HmacIdentifier contextDigest,
            Instant temporaryBlockUntil,
            boolean trustCurrent,
            Duration startGrace,
            Duration ttl);

    Optional<PreAuthState> find(RiskScope scope, HmacIdentifier tokenDigest);

    boolean touch(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            Instant seenAt,
            Duration ttl);

    boolean recordAssessment(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            PreAuthNetworkSnapshot snapshot,
            RiskDecision decision,
            Instant decisionAt,
            HmacIdentifier contextDigest,
            Instant temporaryBlockUntil,
            boolean trustCurrent,
            Duration startGrace,
            Duration ttl);

    PreAuthWebRtcBeginResult beginWebRtcVerification(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            long expectedGeneration,
            Duration verificationWindow,
            Duration ttl);

    PreAuthWebRtcWriteResult writeWebRtcResult(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            long probeGeneration,
            boolean verified,
            PreAuthWebRtcFailureReason failureReason,
            String encryptedWebRtcIps,
            boolean hasReportedIps,
            Duration ttl);

    boolean expireWebRtcDeadline(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            long probeGeneration,
            Duration ttl);

    long recordImpossibleTravelEvent(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier eventDigest,
            Instant occurredAt,
            Duration eventWindow,
            int maximumEvents,
            Duration ttl);

    Optional<PreAuthChallengeActivation> activateChallenge(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            HmacIdentifier contextDigest,
            String proposedNonce,
            Instant now,
            Instant expiresAt,
            Duration ttl);

    boolean consumeChallengeAndTrust(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            HmacIdentifier contextDigest,
            String nonce,
            Instant now,
            Instant challengeVerifiedUntil,
            Duration ttl);

    boolean rotateAuthenticated(
            RiskScope scope,
            HmacIdentifier oldTokenDigest,
            HmacIdentifier newTokenDigest,
            HmacIdentifier deviceDigest,
            RiskSessionType sessionType,
            HmacIdentifier sessionRefDigest,
            HmacIdentifier decisionContextDigest,
            PreAuthWebRtcPhase expectedSourceWebRtcPhase,
            long expectedSourceWebRtcGeneration,
            PreAuthWebRtcPhase webRtcPhase,
            long webRtcGeneration,
            String encryptedWebRtcIps,
            Instant seenAt,
            Duration startGrace,
            Duration ttl);

    /**
     * 仅在源 Token 的已验证 WebRTC generation、当前 IP 和决策上下文均未变化时原子完成认证轮换。
     */
    boolean rotateAuthenticatedAfterWebRtcVerified(
            RiskScope scope,
            HmacIdentifier oldTokenDigest,
            HmacIdentifier newTokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier expectedCurrentIpDigest,
            HmacIdentifier expectedDecisionContextDigest,
            RiskSessionType sessionType,
            HmacIdentifier sessionRefDigest,
            HmacIdentifier newDecisionContextDigest,
            long expectedWebRtcGeneration,
            String encryptedWebRtcIps,
            Instant seenAt,
            Duration ttl);

    void delete(RiskScope scope, HmacIdentifier tokenDigest);
}
