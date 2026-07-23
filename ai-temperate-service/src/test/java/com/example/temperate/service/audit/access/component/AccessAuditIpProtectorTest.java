package com.example.temperate.service.audit.access.component;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.audit.access.domain.ProtectedClientIp;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证访问审计 IP 脱敏前缀与独立 HMAC 标识的稳定性，并确保结果不包含完整原始地址。
 */
class AccessAuditIpProtectorTest {

    private AccessAuditIpProtector protector;

    @BeforeEach
    void setUp() {
        protector = new AccessAuditIpProtector(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void masksIpv4ToSlash24AndCreatesAStableHmac() {
        ProtectedClientIp first = protector.protect("203.0.113.77");
        ProtectedClientIp second = protector.protect("203.0.113.77");

        assertThat(first.ipFamily()).isEqualTo(4);
        assertThat(first.ipPrefix()).isEqualTo("203.0.113.0/24");
        assertThat(first.ipHmac()).hasSize(43).isEqualTo(second.ipHmac());
        assertThat(first.toString()).doesNotContain("203.0.113.77");
    }

    @Test
    void masksIpv6ToSlash48WithoutKeepingTheHostAddress() {
        ProtectedClientIp result = protector.protect("2001:db8:abcd:1234::99");

        assertThat(result.ipFamily()).isEqualTo(6);
        assertThat(result.ipPrefix()).isEqualTo("2001:db8:abcd::/48");
        assertThat(result.toString()).doesNotContain("1234").doesNotContain("::99");
    }

    @Test
    void mapsMissingOrInvalidAddressesToANonSensitiveUnavailableValue() {
        ProtectedClientIp result = protector.protect("not-an-ip");

        assertThat(result.ipFamily()).isZero();
        assertThat(result.ipPrefix()).isEqualTo("unavailable");
        assertThat(result.ipHmac()).hasSize(43);
    }
}
