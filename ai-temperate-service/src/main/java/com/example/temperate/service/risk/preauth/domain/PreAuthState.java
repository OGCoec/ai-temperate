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
 * <p>WebRTC 只增加三态结果和 AES-256-GCM 密文两个可选字段；IP 分数有效期由 Redis Key
 * 存在性统一控制，状态中不再复制固定评估时间。</p>
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
        Boolean webRtcStatus,
        String webRtcIps) {

    public static final int CURRENT_SCHEMA_VERSION = 4;

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
            throw new IllegalArgumentException("PreAuth v4 state is invalid.");
        }
        if (webRtcStatus == null && webRtcIps != null) {
            throw new IllegalArgumentException(
                    "Unverified WebRTC state cannot retain encrypted IPs.");
        }
        if (webRtcStatus != null
                && (webRtcIps == null || webRtcIps.isBlank())) {
            throw new IllegalArgumentException(
                    "Verified WebRTC state requires encrypted IPs.");
        }
        impossibleTravelEvents =
                impossibleTravelEvents == null || impossibleTravelEvents.isBlank()
                        ? "[]"
                        : impossibleTravelEvents;
    }

    public boolean authenticated() {
        return sessionType != RiskSessionType.NONE;
    }

    /**
     * 兼容未写入 WebRTC 可选字段的 v4 构造调用；缺少可选字段时按未校验状态读取。
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
                null,
                null);
    }
}
