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
