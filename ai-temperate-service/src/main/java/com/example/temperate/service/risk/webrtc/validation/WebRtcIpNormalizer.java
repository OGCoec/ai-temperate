package com.example.temperate.service.risk.webrtc.validation;

import com.example.temperate.common.net.ip.IpAddressIdentity;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 将不可信 WebRTC 候选限制为有界公网 IPv4/IPv6 字面量集合，并产生稳定比较顺序。
 *
 * <p>解析先经过项目的纯字面量解析器，因此域名和 mDNS 名称不会触发 DNS；私网、环回、链路本地、
 * 组播、IPv6 ULA 和运营商级 NAT 地址均不能进入加密状态或响应。</p>
 */
public final class WebRtcIpNormalizer {

    private static final int IPV4_PREFIX_BYTES = 3;
    private static final int IPV6_PREFIX_BYTES = 8;
    private static final Comparator<String> STABLE_ORDER = Comparator
            .comparingInt((String value) -> value.indexOf(':') < 0 ? 0 : 1)
            .thenComparing(Comparator.naturalOrder());

    public List<String> normalizeReported(List<String> reportedIps, int maximum) {
        if (reportedIps == null || maximum < 1 || reportedIps.size() > maximum) {
            throw new WebRtcInvalidReportException();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String reportedIp : reportedIps) {
            if (reportedIp == null || reportedIp.length() > 64) {
                throw new WebRtcInvalidReportException();
            }
            IpAddressIdentity identity = IpAddressIdentity.tryParse(reportedIp)
                    .orElseThrow(WebRtcInvalidReportException::new);
            if (!isPublic(identity.toInetAddress())) {
                throw new WebRtcInvalidReportException();
            }
            normalized.add(identity.canonicalText());
        }
        List<String> result = new ArrayList<>(normalized);
        result.sort(STABLE_ORDER);
        return List.copyOf(result);
    }

    public String normalizeTrustedHttpIp(String currentHttpIp) {
        return IpAddressIdentity.tryParse(currentHttpIp)
                .map(IpAddressIdentity::canonicalText)
                .orElseThrow(WebRtcInvalidReportException::new);
    }

    /**
     * 判断规范化地址是否为 IPv4，供一致性策略分别限制 IPv4 与 IPv6 的不同候选数量。
     */
    public boolean isIpv4(String normalizedIp) {
        return parseLiteral(normalizedIp).getAddress().length == 4;
    }

    /**
     * 按固定网络前缀比较可信 HTTP IP 与 WebRTC 候选，且禁止 IPv4 与 IPv6 跨类型匹配。
     * IPv4 使用 /24，IPv6 使用 /64，以兼容同一出口网段内末端地址变化，同时限制放宽范围。
     */
    public boolean matchesTrustedPrefix(
            String canonicalHttpIp,
            String normalizedWebRtcIp) {
        byte[] httpBytes = parseLiteral(canonicalHttpIp).getAddress();
        byte[] webRtcBytes = parseLiteral(normalizedWebRtcIp).getAddress();
        if (httpBytes.length != webRtcBytes.length) {
            return false;
        }
        int prefixBytes = httpBytes.length == 4
                ? IPV4_PREFIX_BYTES
                : IPV6_PREFIX_BYTES;
        for (int index = 0; index < prefixBytes; index++) {
            if (httpBytes[index] != webRtcBytes[index]) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress parseLiteral(String value) {
        return IpAddressIdentity.tryParse(value)
                .map(IpAddressIdentity::toInetAddress)
                .orElseThrow(WebRtcInvalidReportException::new);
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first != 0
                    && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 198 && (second == 18 || second == 19));
        }
        // fc00::/7 是 IPv6 Unique Local Address，JDK 的 site-local 判定不覆盖该地址段。
        return (Byte.toUnsignedInt(bytes[0]) & 0xFE) != 0xFC;
    }
}
