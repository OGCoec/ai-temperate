package com.example.temperate.service.risk.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定网络风险、外部 IP 情报、PreAuth 和一次性 WAF Challenge 的安全配置。
 *
 * <p>该配置只定义有界超时、缓存期限和独立密钥；请求级风险状态始终保存在 Redis，不保存在配置对象中。</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.network-risk")
public record NetworkRiskProperties(
        @NotNull NetworkRiskMode mode,
        String hmacSecretBase64,
        String ip2LocationApiKeyEncryptionKeyBase64,
        @NotNull URI ip2LocationBaseUrl,
        @NotNull URI ipingBaseUrl,
        boolean ipingEnabled,
        @NotNull Duration lookupTimeout,
        @NotNull Duration positiveCacheTtl,
        @NotNull Duration fallbackCacheTtl,
        @NotNull Duration singleFlightTtl,
        @Min(1) @Max(128) int maxConcurrentLookups,
        @NotNull Duration anonymousPreAuthTtl,
        @NotNull Duration authenticatedPreAuthTtl,
        @NotNull Duration challengeTtl,
        @NotNull Duration challengeVerifiedTtl,
        @DecimalMin(value = "1.0") double impossibleTravelMinimumDistanceKm,
        @NotNull Duration impossibleTravelWindow,
        @NotNull Duration temporaryBlockTtl,
        @NotNull WebRtc webRtc) {

    private static final Duration API_KEY_FILTER_COMPLETION_MARGIN = Duration.ofMillis(500);

    private static final List<String> REQUIRED_STUN_URLS = List.of(
            "stun:stun.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
            "stun:global.stun.twilio.com:3478",
            "stun:stun.nextcloud.com:3478");

    public NetworkRiskProperties {
        requirePositive(lookupTimeout, "lookupTimeout");
        requirePositive(positiveCacheTtl, "positiveCacheTtl");
        requirePositive(fallbackCacheTtl, "fallbackCacheTtl");
        requirePositive(singleFlightTtl, "singleFlightTtl");
        requirePositive(anonymousPreAuthTtl, "anonymousPreAuthTtl");
        requirePositive(authenticatedPreAuthTtl, "authenticatedPreAuthTtl");
        requirePositive(challengeTtl, "challengeTtl");
        requirePositive(challengeVerifiedTtl, "challengeVerifiedTtl");
        if (!Double.isFinite(impossibleTravelMinimumDistanceKm)
                || impossibleTravelMinimumDistanceKm < 1D) {
            throw new IllegalArgumentException(
                    "impossibleTravelMinimumDistanceKm must be at least one");
        }
        requirePositive(impossibleTravelWindow, "impossibleTravelWindow");
        requirePositive(temporaryBlockTtl, "temporaryBlockTtl");
        if (webRtc == null) {
            throw new IllegalArgumentException("webRtc configuration is required");
        }
    }

    @AssertTrue(message = "Network risk secrets must be canonical Base64 and the WebRTC key must contain exactly 32 bytes")
    public boolean isSecretsValid() {
        if (mode == NetworkRiskMode.DISABLED) {
            // 关闭模式允许本地环境不配置密钥；如果显式提供，则仍拒绝短密钥或非规范 Base64。
            return absentOrValidSecret(hmacSecretBase64)
                    && absentOrValidSecret(ip2LocationApiKeyEncryptionKeyBase64)
                    && absentOrValidExactSecret(
                            webRtc.ipEncryptionKeyBase64());
        }
        return validSecret(hmacSecretBase64)
                && validSecret(ip2LocationApiKeyEncryptionKeyBase64)
                && validExactSecret(webRtc.ipEncryptionKeyBase64());
    }

    @AssertTrue(message = "Network risk lookup timeout must not exceed eight seconds")
    public boolean isLookupTimeoutValid() {
        return lookupTimeout != null
                && lookupTimeout.compareTo(Duration.ofMillis(100)) >= 0
                && lookupTimeout.compareTo(Duration.ofSeconds(8)) <= 0;
    }

    /**
     * 返回 API Key 安全 Filter 的最终等待上限。
     *
     * <p>外部查询必须受 {@link #lookupTimeout()} 约束；额外五百毫秒只预留给 Reactor 将超时转换为
     * 本地降级结果，避免两个相同截止点竞争时把确定的失败关闭响应误判为 Filter 超时。</p>
     *
     * @return 外部查询预算与本地完成余量之和
     */
    public Duration apiKeyFilterWaitTimeout() {
        return lookupTimeout.plus(API_KEY_FILTER_COMPLETION_MARGIN);
    }

    @AssertTrue(message = "IP intelligence endpoints must use HTTPS")
    public boolean isProviderOriginsValid() {
        return secureOrigin(ip2LocationBaseUrl) && secureOrigin(ipingBaseUrl);
    }

    @AssertTrue(message = "WebRTC settings must use the fixed STUN order and a report window no longer than fifteen seconds")
    public boolean isWebRtcConfigValid() {
        return webRtc != null
                && webRtc.pendingWindow().compareTo(Duration.ofSeconds(15)) <= 0
                && webRtc.maxReportedIps() == 8
                && webRtc.stunUrls().stream().map(URI::toString).toList()
                        .equals(REQUIRED_STUN_URLS);
    }

    @Override
    public String toString() {
        return "NetworkRiskProperties[redacted]";
    }

    private static boolean secureOrigin(URI value) {
        return value != null
                && "https".equalsIgnoreCase(value.getScheme())
                && value.getHost() != null
                && value.getUserInfo() == null
                && value.getFragment() == null;
    }

    private static boolean validSecret(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length >= 32
                    && Base64.getEncoder().encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean absentOrValidSecret(String value) {
        return value == null || value.isBlank() || validSecret(value);
    }

    private static boolean validExactSecret(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length == 32
                    && Base64.getEncoder().encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean absentOrValidExactSecret(String value) {
        return value == null || value.isBlank() || validExactSecret(value);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /**
     * 约束客户端 WebRTC 探测的固定 STUN 清单、有界等待时间、报告数量和独立加密密钥。
     */
    public record WebRtc(
            @NotNull Duration startGrace,
            @NotNull Duration probeTimeout,
            @NotNull Duration reportGrace,
            @NotNull List<@NotNull URI> stunUrls,
            @Min(1) @Max(32) int maxReportedIps,
            String ipEncryptionKeyBase64) {

        public WebRtc {
            requirePositive(startGrace, "webRtc.startGrace");
            requirePositive(probeTimeout, "webRtc.probeTimeout");
            requirePositive(reportGrace, "webRtc.reportGrace");
            stunUrls = stunUrls == null ? List.of() : List.copyOf(stunUrls);
        }

        public Duration pendingWindow() {
            return probeTimeout.plus(reportGrace);
        }

        @Override
        public String toString() {
            return "WebRtc[probeTimeout=" + probeTimeout
                    + ", startGrace=" + startGrace
                    + ", reportGrace=" + reportGrace
                    + ", stunUrls=" + stunUrls
                    + ", maxReportedIps=" + maxReportedIps
                    + ", ipEncryptionKeyBase64=redacted]";
        }
    }
}
