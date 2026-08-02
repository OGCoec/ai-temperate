package com.example.temperate.service.user.aiconversation.model.impl;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

/**
 * 在模型媒体 HTTP 客户端真正建连时过滤 DNS 结果，防止私网访问和 DNS 重绑定。
 */
final class AiConversationPublicDnsResolver implements DnsResolver {

    private final DnsResolver delegate;

    AiConversationPublicDnsResolver() {
        this(SystemDefaultDnsResolver.INSTANCE);
    }

    AiConversationPublicDnsResolver(DnsResolver delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = delegate.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw denied();
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw denied();
            }
        }
        return addresses.clone();
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }

    private static boolean isPublic(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            return isPublicIpv4(bytes);
        }
        return address instanceof Inet6Address
                && bytes.length == 16
                && isPublicIpv6(bytes);
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        if (first == 0
                || first == 10
                || first == 127
                || first >= 224
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 198 && (second == 18 || second == 19))) {
            return false;
        }
        return !(first == 192 && second == 0 && (third == 0 || third == 2))
                && !(first == 198 && second == 51 && third == 100)
                && !(first == 203 && second == 0 && third == 113);
    }

    private static boolean isPublicIpv6(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        // 仅允许 IANA 全球单播 2000::/3，并额外拒绝文档专用 2001:db8::/32。
        if ((first & 0xE0) != 0x20) {
            return false;
        }
        return !(first == 0x20
                && Byte.toUnsignedInt(bytes[1]) == 0x01
                && Byte.toUnsignedInt(bytes[2]) == 0x0D
                && Byte.toUnsignedInt(bytes[3]) == 0xB8);
    }

    private static UnknownHostException denied() {
        return new UnknownHostException(
                "model media host did not resolve exclusively to public addresses");
    }
}
