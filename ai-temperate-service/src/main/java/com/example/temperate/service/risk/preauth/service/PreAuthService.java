package com.example.temperate.service.risk.preauth.service;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthChallengeActivation;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.domain.PreAuthNetworkSnapshot;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import java.time.Instant;
import java.util.Optional;

/**
 * 定义 PreAuth 签发、原始或摘要级设备/会话绑定校验、滑动续期、风险决策记录与可信网络更新业务。
 */
public interface PreAuthService {

    PreAuthIssue createEvaluated(
            RiskScope scope,
            String rawDeviceId,
            PreAuthNetworkSnapshot snapshot,
            RiskDecision decision,
            Instant temporaryBlockUntil,
            boolean trustCurrent);

    Optional<PreAuthAccess> resolve(
            RiskScope scope,
            String rawToken,
            String rawDeviceId);

    Optional<PreAuthAccess> resolveChallengeNavigation(
            RiskScope scope,
            String rawToken);

    Optional<PreAuthAccess> resolveBound(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            RiskSessionType sessionType,
            HmacIdentifier sessionReferenceDigest);

    boolean touch(PreAuthAccess access, Instant seenAt);

    PreAuthSessionBinding requireSessionBinding(
            PreAuthAccess access,
            RiskScope expectedScope,
            RiskSessionType expectedSessionType,
            String rawSessionReference);

    boolean recordAssessment(
            PreAuthAccess access,
            PreAuthNetworkSnapshot snapshot,
            RiskDecision decision,
            Instant decisionAt,
            HmacIdentifier contextDigest,
            Instant temporaryBlockUntil,
            boolean trustCurrent);

    long recordImpossibleTravelEvent(
            PreAuthAccess access,
            HmacIdentifier eventDigest,
            Instant occurredAt);

    Optional<PreAuthChallengeActivation> activateChallenge(
            PreAuthAccess access,
            HmacIdentifier currentIpDigest,
            HmacIdentifier contextDigest,
            String proposedNonce,
            Instant now);

    boolean consumeChallengeAndTrust(
            PreAuthAccess access,
            HmacIdentifier currentIpDigest,
            HmacIdentifier contextDigest,
            String nonce,
            Instant now);

    PreAuthIssue promoteAuthenticated(
            PreAuthAccess access,
            RiskSessionType sessionType,
            String rawSessionReference,
            Instant seenAt);

    void revoke(RiskScope scope, String rawToken);
}
