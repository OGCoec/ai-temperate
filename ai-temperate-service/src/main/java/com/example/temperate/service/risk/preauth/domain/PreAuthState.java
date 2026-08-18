package com.example.temperate.service.risk.preauth.domain;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.ipintel.domain.NetworkType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 保存普通或管理员 PreAuth 的有界网络风控状态，不包含明文 IP、设备、原始令牌或会话令牌。
 *
 * <p>WebRTC 状态包含四态、单调 generation、服务端绝对截止时间、受控失败原因和可选的
 * AES-256-GCM 候选集合密文；IP 分数有效期仍由 Redis Key 存在性统一控制。</p>
 */
public record PreAuthState(
        int schemaVersion,
        RiskScope scope,
        String authState,
        RiskSessionType sessionType,
        HmacIdentifier sessionRefDigest,
        HmacIdentifier deviceDigest,
        Instant lastSeenAt,
        HmacIdentifier currentIpDigest,
        int currentTrustScore,
        String currentCountryCode,
        Long currentAsn,
        BigDecimal currentLatitude,
        BigDecimal currentLongitude,
        NetworkType currentNetworkType,
        boolean currentScoreIncludesNetworkRisk,
        PreAuthRiskSource currentRiskSource,
        PreAuthGeoSource currentGeoSource,
        HmacIdentifier lastTrustedIpDigest,
        String lastTrustedCountryCode,
        Long lastTrustedAsn,
        BigDecimal lastTrustedLatitude,
        BigDecimal lastTrustedLongitude,
        Instant lastTrustedObservedAt,
        RiskDecision lastDecision,
        Instant lastDecisionAt,
        HmacIdentifier lastDecisionContextDigest,
        Instant temporaryBlockUntil,
        Instant challengeVerifiedUntil,
        long impossibleTravelCount,
        String impossibleTravelEvents,
        long challengeIssuedCount,
        long challengePassedCount,
        String activeChallengeNonce,
        HmacIdentifier activeChallengeIpDigest,
        HmacIdentifier activeChallengeContextDigest,
        Instant activeChallengeExpiresAt,
        PreAuthWebRtcPhase webRtcPhase,
        long webRtcGeneration,
        Instant webRtcDeadlineAt,
        PreAuthWebRtcFailureReason webRtcFailureReason,
        String webRtcIps) {

    public static final int CURRENT_SCHEMA_VERSION = 7;

    public PreAuthState {
        if (schemaVersion != CURRENT_SCHEMA_VERSION
                || scope == null
                || authState == null
                || sessionType == null
                || deviceDigest == null
                || lastSeenAt == null
                || currentIpDigest == null
                || currentTrustScore < 0
                || currentTrustScore > 100
                || currentNetworkType == null
                || currentRiskSource == null
                || currentGeoSource == null
                || lastDecision == null
                || lastDecisionAt == null
                || impossibleTravelCount < 0
                || challengeIssuedCount < 0
                || challengePassedCount < 0) {
            throw new IllegalArgumentException("PreAuth v7 state is invalid.");
        }
        if (webRtcPhase == null || webRtcGeneration <= 0) {
            throw new IllegalArgumentException(
                    "WebRTC phase and generation are required.");
        }
        boolean validWebRtcState = switch (webRtcPhase) {
            case REQUIRED, PENDING -> webRtcDeadlineAt != null
                    && webRtcFailureReason == null
                    && webRtcIps == null;
            case VERIFIED -> webRtcDeadlineAt == null
                    && webRtcFailureReason == null
                    && webRtcIps != null
                    && !webRtcIps.isBlank();
            case FAILED -> webRtcFailureReason != null
                    && webRtcDeadlineAt == null
                    && (retainsWebRtcEvidence(webRtcFailureReason)
                            ? webRtcIps != null && !webRtcIps.isBlank()
                            : webRtcIps == null);
        };
        if (!validWebRtcState) {
            throw new IllegalArgumentException("WebRTC state is inconsistent.");
        }
        impossibleTravelEvents =
                impossibleTravelEvents == null || impossibleTravelEvents.isBlank()
                        ? "[]"
                        : impossibleTravelEvents;
    }

    public boolean authenticated() {
        return sessionType != RiskSessionType.NONE;
    }

    private static boolean retainsWebRtcEvidence(
            PreAuthWebRtcFailureReason failureReason) {
        return failureReason == PreAuthWebRtcFailureReason.IP_MISMATCH
                || failureReason == PreAuthWebRtcFailureReason.IP_FAMILY_INCOMPLETE;
    }

    /**
     * 为旧的非 WebRTC 测试构造调用补齐固定八秒 start grace；生产创建仍由 Redis Store 显式写入。
     */
    public PreAuthState(
            int schemaVersion,
            RiskScope scope,
            String authState,
            RiskSessionType sessionType,
            HmacIdentifier sessionRefDigest,
            HmacIdentifier deviceDigest,
            Instant lastSeenAt,
            HmacIdentifier currentIpDigest,
            int currentTrustScore,
            String currentCountryCode,
            Long currentAsn,
            BigDecimal currentLatitude,
            BigDecimal currentLongitude,
            NetworkType currentNetworkType,
            boolean currentScoreIncludesNetworkRisk,
            PreAuthRiskSource currentRiskSource,
            PreAuthGeoSource currentGeoSource,
            HmacIdentifier lastTrustedIpDigest,
            String lastTrustedCountryCode,
            Long lastTrustedAsn,
            BigDecimal lastTrustedLatitude,
            BigDecimal lastTrustedLongitude,
            Instant lastTrustedObservedAt,
            RiskDecision lastDecision,
            Instant lastDecisionAt,
            HmacIdentifier lastDecisionContextDigest,
            Instant temporaryBlockUntil,
            Instant challengeVerifiedUntil,
            long impossibleTravelCount,
            String impossibleTravelEvents,
            long challengeIssuedCount,
            long challengePassedCount,
            String activeChallengeNonce,
            HmacIdentifier activeChallengeIpDigest,
            HmacIdentifier activeChallengeContextDigest,
            Instant activeChallengeExpiresAt) {
        this(
                schemaVersion,
                scope,
                authState,
                sessionType,
                sessionRefDigest,
                deviceDigest,
                lastSeenAt,
                currentIpDigest,
                currentTrustScore,
                currentCountryCode,
                currentAsn,
                currentLatitude,
                currentLongitude,
                currentNetworkType,
                currentScoreIncludesNetworkRisk,
                currentRiskSource,
                currentGeoSource,
                lastTrustedIpDigest,
                lastTrustedCountryCode,
                lastTrustedAsn,
                lastTrustedLatitude,
                lastTrustedLongitude,
                lastTrustedObservedAt,
                lastDecision,
                lastDecisionAt,
                lastDecisionContextDigest,
                temporaryBlockUntil,
                challengeVerifiedUntil,
                impossibleTravelCount,
                impossibleTravelEvents,
                challengeIssuedCount,
                challengePassedCount,
                activeChallengeNonce,
                activeChallengeIpDigest,
                activeChallengeContextDigest,
                activeChallengeExpiresAt,
                PreAuthWebRtcPhase.REQUIRED,
                1L,
                lastSeenAt.plusSeconds(8),
                null,
                null);
    }

    public Boolean webRtcStatus() {
        return switch (webRtcPhase) {
            case REQUIRED, PENDING -> null;
            case VERIFIED -> Boolean.TRUE;
            case FAILED -> Boolean.FALSE;
        };
    }
}
