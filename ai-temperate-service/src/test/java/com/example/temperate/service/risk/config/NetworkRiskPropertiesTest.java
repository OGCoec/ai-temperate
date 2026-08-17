package com.example.temperate.service.risk.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证网络风险关闭模式与启用模式对部署 Secret 的不同启动约束。
 */
class NetworkRiskPropertiesTest {

    private static final String VALID_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String WEBRTC_SECRET =
            "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";

    @Test
    void configurationRecordKeepsOneUnambiguousBindingConstructor() {
        long bindableConstructors = Arrays.stream(
                        NetworkRiskProperties.class.getDeclaredConstructors())
                .filter(constructor -> !constructor.isSynthetic())
                .count();
        long webRtcBindableConstructors = Arrays.stream(
                        NetworkRiskProperties.WebRtc.class.getDeclaredConstructors())
                .filter(constructor -> !constructor.isSynthetic())
                .count();

        // Spring Boot 对只有一个构造器的配置 record 自动采用构造器绑定，避免退回无参 JavaBean 实例化。
        assertThat(bindableConstructors).isOne();
        assertThat(webRtcBindableConstructors).isOne();
    }

    @Test
    void disabledModeAllowsAbsentSecretsWithoutProvidingAStaticDefault() {
        NetworkRiskProperties properties = properties(
                NetworkRiskMode.DISABLED,
                "",
                "",
                "");

        assertThat(properties.isSecretsValid()).isTrue();
    }

    @Test
    void enabledModesRequireBothCanonicalSecrets() {
        assertThat(properties(
                        NetworkRiskMode.OBSERVE,
                        "",
                        VALID_SECRET,
                        WEBRTC_SECRET)
                .isSecretsValid())
                .isFalse();
        assertThat(properties(
                        NetworkRiskMode.OBSERVE,
                        VALID_SECRET,
                        VALID_SECRET,
                        "")
                .isSecretsValid())
                .isFalse();
        assertThat(properties(
                        NetworkRiskMode.ENFORCE,
                        VALID_SECRET,
                        VALID_SECRET,
                        WEBRTC_SECRET)
                .isSecretsValid())
                .isTrue();
    }

    @Test
    void webRtcPendingWindowCombinesFifteenSecondProbeAndFiveSecondGrace() {
        NetworkRiskProperties properties = properties(
                NetworkRiskMode.ENFORCE,
                VALID_SECRET,
                VALID_SECRET,
                WEBRTC_SECRET);

        assertThat(properties.webRtc().startGrace()).isEqualTo(Duration.ofSeconds(8));
        assertThat(properties.webRtc().probeTimeout()).isEqualTo(Duration.ofSeconds(12));
        assertThat(properties.webRtc().reportGrace()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.webRtc().pendingWindow()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.isWebRtcConfigValid()).isTrue();
    }

    @Test
    void apiKeyFilterWaitTimeoutAddsOnlyTheCompletionMargin() {
        NetworkRiskProperties properties = properties(
                NetworkRiskMode.ENFORCE,
                VALID_SECRET,
                VALID_SECRET,
                WEBRTC_SECRET);

        assertThat(properties.apiKeyFilterWaitTimeout())
                .isEqualTo(Duration.ofMillis(8500));
    }

    private static NetworkRiskProperties properties(
            NetworkRiskMode mode,
            String hmacSecret,
            String encryptionSecret,
            String webRtcSecret) {
        return new NetworkRiskProperties(
                mode,
                hmacSecret,
                encryptionSecret,
                URI.create("https://ip2location.test/"),
                URI.create("https://iping.test/v1/query"),
                true,
                Duration.ofSeconds(8),
                Duration.ofHours(6),
                Duration.ofSeconds(30),
                Duration.ofSeconds(9),
                32,
                Duration.ofMinutes(30),
                Duration.ofHours(6),
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                200D,
                Duration.ofHours(24),
                Duration.ofMinutes(10),
                new NetworkRiskProperties.WebRtc(
                Duration.ofSeconds(8),
                Duration.ofSeconds(12),
                Duration.ofSeconds(3),
                        List.of(
                                URI.create("stun:stun.l.google.com:19302"),
                                URI.create("stun:stun.cloudflare.com:3478"),
                                URI.create("stun:global.stun.twilio.com:3478"),
                                URI.create("stun:stun.nextcloud.com:3478")),
                        8,
                        webRtcSecret));
    }
}
