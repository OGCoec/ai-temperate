package com.example.temperate.web.auth.phonecountry.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证公网客户端地址安全边界覆盖 IPv4、IPv6 以及常见代理 Fake-IP 网段的测试。
 */
class PublicIpAddressPolicyTest {

    @Test
    void acceptsPublicIpv4AndIpv6Addresses() {
        assertThat(List.of("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888"))
                .allSatisfy(value -> assertThat(isPublic(value)).isTrue());
    }

    @Test
    void rejectsNonPublicIpv4Addresses() {
        assertThat(List.of(
                        "0.1.2.3",
                        "10.0.0.1",
                        "100.64.0.1",
                        "127.0.0.1",
                        "169.254.1.1",
                        "172.16.0.1",
                        "192.0.0.1",
                        "192.0.2.1",
                        "192.88.99.1",
                        "192.168.1.1",
                        "198.18.0.1",
                        "198.51.100.1",
                        "203.0.113.1",
                        "224.0.0.1",
                        "240.0.0.1"))
                .allSatisfy(value -> assertThat(isPublic(value)).isFalse());
    }

    @Test
    void rejectsNonPublicIpv6Addresses() {
        assertThat(List.of(
                        "::",
                        "::1",
                        "64:ff9b:1::1",
                        "100::1",
                        "2001:2::1",
                        "2001:10::1",
                        "2001:20::1",
                        "2001:db8::1",
                        "3fff::1",
                        "5f00::1",
                        "fc00::1",
                        "fe80::1",
                        "ff02::1"))
                .allSatisfy(value -> assertThat(isPublic(value)).isFalse());
    }

    @Test
    void rejectsIpv4MappedIpv6Address() throws Exception {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xFF;
        bytes[11] = (byte) 0xFF;
        bytes[12] = (byte) 192;
        bytes[13] = (byte) 168;
        bytes[14] = 1;
        bytes[15] = 1;
        InetAddress mappedAddress = Inet6Address.getByAddress(null, bytes, -1);

        assertThat(PublicIpAddressPolicy.isPublic(mappedAddress)).isFalse();
    }

    private static boolean isPublic(String value) {
        InetAddress address = IpNetworkRange.parseAddressLiteral(value).orElseThrow();
        return PublicIpAddressPolicy.isPublic(address);
    }
}
