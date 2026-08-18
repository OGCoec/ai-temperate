package com.example.temperate.service.risk.webrtc.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 WebRTC 报告仅接受有界公网 IP 字面量，并统一 IPv4-Mapped IPv6 与稳定排序。
 */
class WebRtcIpNormalizerTest {

    private final WebRtcIpNormalizer normalizer = new WebRtcIpNormalizer();

    @Test
    void normalizesMappedIpv4DeduplicatesAndSortsBothFamilies() {
        assertThat(normalizer.normalizeReported(
                        List.of(
                                "2606:4700:4700:0:0:0:0:1111",
                                "::ffff:8.8.8.8",
                                "8.8.8.8"),
                        8))
                .containsExactly("8.8.8.8", "2606:4700:4700::1111");
    }

    @Test
    void acceptsEmptyReportButRejectsUntrustedAddressForms() {
        assertThat(normalizer.normalizeReported(List.of(), 8)).isEmpty();

        for (String invalid : List.of(
                "candidate.local",
                "8.8.8.8:3478",
                "8.8.8.0/24",
                "fe80::1%eth0",
                "127.0.0.1",
                "10.0.0.1",
                "100.64.0.1",
                "192.0.2.1",
                "198.51.100.1",
                "203.0.113.1",
                "fe80::1",
                "fd00::1",
                "::8.8.8.8",
                "2001:db8::1")) {
            assertThatThrownBy(() -> normalizer.normalizeReported(
                            List.of(invalid),
                            8))
                    .isInstanceOf(WebRtcInvalidReportException.class);
        }
    }

    @Test
    void rejectsMoreThanConfiguredMaximum() {
        assertThatThrownBy(() -> normalizer.normalizeReported(
                        List.of(
                                "8.8.8.1", "8.8.8.2", "8.8.8.3",
                                "8.8.8.4", "8.8.8.5", "8.8.8.6",
                                "8.8.8.7", "8.8.8.8", "8.8.4.4"),
                        8))
                .isInstanceOf(WebRtcInvalidReportException.class);
    }

    @Test
    void matchesIpv4BySlash24AndRequiresTheSameIpVersion() {
        assertThat(normalizer.isIpv4("66.90.98.38")).isTrue();
        assertThat(normalizer.matchesTrustedPrefix(
                "66.90.98.36",
                "66.90.98.38")).isTrue();
        assertThat(normalizer.matchesTrustedPrefix(
                "66.90.98.36",
                "66.90.99.38")).isFalse();
        assertThat(normalizer.matchesTrustedPrefix(
                "66.90.98.36",
                "2606:4700:4700::1111")).isFalse();
    }

    @Test
    void matchesIpv6BySlash64AndRequiresTheSameIpVersion() {
        assertThat(normalizer.isIpv4("2606:4700:4700::1111")).isFalse();
        assertThat(normalizer.matchesTrustedPrefix(
                "2606:4700:4700::1111",
                "2606:4700:4700::2222")).isTrue();
        assertThat(normalizer.matchesTrustedPrefix(
                "2606:4700:4700::1111",
                "2606:4700:4701::2222")).isFalse();
        assertThat(normalizer.matchesTrustedPrefix(
                "2606:4700:4700::1111",
                "66.90.98.38")).isFalse();
    }
}
