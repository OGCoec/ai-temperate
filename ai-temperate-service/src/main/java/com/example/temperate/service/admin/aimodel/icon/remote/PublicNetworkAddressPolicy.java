package com.example.temperate.service.admin.aimodel.icon.remote;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * 判断 DNS 解析结果是否全部属于允许访问的公网地址。
 *
 * <p>任何一个私网、回环、链路本地、组播、文档保留或运营商共享地址都会使整个主机被拒绝，
 * 防止多地址 DNS 记录通过夹带内网地址绕过 SSRF 边界。</p>
 */
final class PublicNetworkAddressPolicy {

    private PublicNetworkAddressPolicy() {
    }

    static InetAddress[] requirePublic(InetAddress[] addresses) {
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("host did not resolve to an address");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new IllegalArgumentException("host resolved to a non-public address");
            }
        }
        return addresses.clone();
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
        // 当前业务只允许 IANA 全球单播 2000::/3，并额外拒绝文档专用 2001:db8::/32。
        if ((first & 0xE0) != 0x20) {
            return false;
        }
        return !(first == 0x20
                && Byte.toUnsignedInt(bytes[1]) == 0x01
                && Byte.toUnsignedInt(bytes[2]) == 0x0D
                && Byte.toUnsignedInt(bytes[3]) == 0xB8);
    }
}
