package com.example.temperate.service.risk.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 验证网络风险 IP 摘要仅由地址族和网络字节决定，并与旧字符串格式隔离。
 */
final class NetworkRiskIdentifierTest {

    private static final byte[] SECRET =
            "network-risk-test-secret-0123456789".getBytes(StandardCharsets.UTF_8);

    private final HmacSha256Identifier hmac = new HmacSha256Identifier(SECRET);
    private final NetworkRiskIdentifier identifier = new NetworkRiskIdentifier(hmac);

    @Test
    void equivalentIpv6SpellingsProduceTheSameV2Digest() {
        assertThat(identifier.identifyIp("2001:db8::1"))
                .isEqualTo(identifier.identifyIp(
                        "2001:0DB8:0000:0000:0000:0000:0000:0001"));
        assertThat(identifier.canonicalIp("2001:db8:0:0:0:0:0:1"))
                .isEqualTo("2001:db8::1");
    }

    @Test
    void ipv4MappedIpv6UsesTheIpv4Digest() {
        assertThat(identifier.identifyIp("::ffff:192.0.2.1"))
                .isEqualTo(identifier.identifyIp("192.0.2.1"));
    }

    @Test
    void v2BinaryDigestIsSeparatedFromTheLegacyStringDigest() {
        assertThat(identifier.identifyIp("203.0.113.10"))
                .isNotEqualTo(hmac.identify("risk-ip\u0000203.0.113.10"));
    }

    @Test
    void rejectsHostnamesAndScopedIpv6() {
        assertThatThrownBy(() -> identifier.identifyIp("example.test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> identifier.identifyIp("fe80::1%eth0"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
