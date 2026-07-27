package com.example.temperate.service.risk.preauth.store;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthChallengeActivation;
import com.example.temperate.service.risk.preauth.domain.PreAuthNetworkSnapshot;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcWriteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 定义 PreAuth Hash 的创建、读取、滑动续期、决策记录和可信网络更新原子边界。
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
            Duration ttl);

    PreAuthWebRtcWriteResult writeWebRtcResult(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            boolean webRtcStatus,
            String encryptedWebRtcIps,
            boolean hasReportedIps,
            Duration ttl);

    boolean clearWebRtcResult(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
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
            Boolean webRtcStatus,
            String encryptedWebRtcIps,
            Instant seenAt,
            Duration ttl);

    void delete(RiskScope scope, HmacIdentifier tokenDigest);
}
