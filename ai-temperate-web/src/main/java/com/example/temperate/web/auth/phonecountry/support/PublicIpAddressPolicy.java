package com.example.temperate.web.auth.phonecountry.support;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

/**
 * 该类用于判断已解析的 IP 字面量能否作为公网客户端地址进入国家库查询。
 *
 * <p>它是可信代理之后的安全边界，不负责判断地址的国家归属，也不会执行 DNS 查询。回环、私网、链路本地、
 * 文档、基准测试、Fake-IP、组播和保留地址都会被拒绝，避免把代理地址或伪造地址交给 IP2Location。</p>
 */
public final class PublicIpAddressPolicy {

    private static final List<IpNetworkRange> NON_PUBLIC_IPV4_RANGES = ranges(
            "0.0.0.0/8",
            "10.0.0.0/8",
            "100.64.0.0/10",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "172.16.0.0/12",
            "192.0.0.0/24",
            "192.0.2.0/24",
            "192.88.99.0/24",
            "192.168.0.0/16",
            "198.18.0.0/15",
            "198.51.100.0/24",
            "203.0.113.0/24",
            "224.0.0.0/4",
            "240.0.0.0/4");

    private static final List<IpNetworkRange> NON_PUBLIC_IPV6_RANGES = ranges(
            "64:ff9b::/96",
            "64:ff9b:1::/48",
            "100::/64",
            "2001:2::/48",
            "2001:10::/28",
            "2001:20::/28",
            "2001:db8::/32",
            "3fff::/20",
            "5f00::/16",
            "fc00::/7",
            "fe80::/10",
            "ff00::/8");

    private PublicIpAddressPolicy() {
    }

    /**
     * 判断地址是否属于可以用于公网客户端识别的单播地址。
     *
     * @param address 已由字面量解析器生成的地址，不接受主机名
     * @return 只有明确可作为公网客户端来源时返回 {@code true}
     */
    public static boolean isPublic(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return NON_PUBLIC_IPV4_RANGES.stream().noneMatch(range -> range.contains(address));
        }
        if (bytes.length != 16) {
            return false;
        }
        // IPv4-compatible 和 IPv4-mapped IPv6 会绕过 IPv4 CIDR 表，因此在 IPv6 匹配前单独拒绝。
        if (isIpv4Compatible(bytes) || isIpv4Mapped(bytes)) {
            return false;
        }
        return NON_PUBLIC_IPV6_RANGES.stream().noneMatch(range -> range.contains(address));
    }

    private static boolean isIpv4Compatible(byte[] bytes) {
        for (int index = 0; index < 12; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    }

    private static List<IpNetworkRange> ranges(String... cidrs) {
        return Arrays.stream(cidrs)
                .map(cidr -> IpNetworkRange.parse(cidr)
                        .orElseThrow(() -> new IllegalStateException("Invalid built-in IP range")))
                .toList();
    }
}
