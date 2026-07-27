package com.example.temperate.service.risk.webrtc.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.domain.RiskScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 WebRTC IP 集合使用随机 IV 加密，并通过 AAD 阻止跨作用域、Token 或网络摘要替换。
 */
class WebRtcIpProtectorTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
    private static final HmacIdentifier TOKEN = digest('A');
    private static final HmacIdentifier IP = digest('B');

    @Test
    void encryptsSameIpsWithDifferentIvAndRoundTrips() {
        WebRtcIpProtector protector = new WebRtcIpProtector(KEY, new ObjectMapper());

        String first = protector.encrypt(
                List.of("8.8.8.8", "2606:4700:4700::1111"),
                RiskScope.USER,
                TOKEN,
                IP);
        String second = protector.encrypt(
                List.of("8.8.8.8", "2606:4700:4700::1111"),
                RiskScope.USER,
                TOKEN,
                IP);

        assertThat(first).startsWith("v1.").isNotEqualTo(second);
        assertThat(protector.decrypt(first, RiskScope.USER, TOKEN, IP))
                .containsExactly("8.8.8.8", "2606:4700:4700::1111");
    }

    @Test
    void rejectsTamperingAndWrongAad() {
        WebRtcIpProtector protector = new WebRtcIpProtector(KEY, new ObjectMapper());
        String encrypted = protector.encrypt(
                List.of("8.8.8.8"),
                RiskScope.USER,
                TOKEN,
                IP);
        char replacement = encrypted.endsWith("A") ? 'B' : 'A';

        assertThatThrownBy(() -> protector.decrypt(
                        encrypted.substring(0, encrypted.length() - 1) + replacement,
                        RiskScope.USER,
                        TOKEN,
                        IP))
                .isInstanceOf(WebRtcIpProtectionException.class);
        assertThatThrownBy(() -> protector.decrypt(
                        encrypted,
                        RiskScope.ADMIN,
                        TOKEN,
                        IP))
                .isInstanceOf(WebRtcIpProtectionException.class);
        assertThatThrownBy(() -> protector.decrypt(
                        encrypted,
                        RiskScope.USER,
                        digest('C'),
                        IP))
                .isInstanceOf(WebRtcIpProtectionException.class);
        assertThatThrownBy(() -> protector.decrypt(
                        encrypted,
                        RiskScope.USER,
                        TOKEN,
                        digest('D')))
                .isInstanceOf(WebRtcIpProtectionException.class);
    }

    @Test
    void rejectsCiphertextCreatedWithAnotherEncryptionKey() {
        WebRtcIpProtector first = new WebRtcIpProtector(KEY, new ObjectMapper());
        String otherKey = Base64.getEncoder().encodeToString(
                "abcdef0123456789abcdef0123456789".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
        WebRtcIpProtector second = new WebRtcIpProtector(
                otherKey,
                new ObjectMapper());
        String encrypted = first.encrypt(
                List.of("8.8.8.8"),
                RiskScope.USER,
                TOKEN,
                IP);

        assertThatThrownBy(() -> second.decrypt(
                        encrypted,
                        RiskScope.USER,
                        TOKEN,
                        IP))
                .isInstanceOf(WebRtcIpProtectionException.class);
    }

    private static HmacIdentifier digest(char value) {
        return HmacIdentifier.fromProtectedValue(String.valueOf(value).repeat(43));
    }
}
