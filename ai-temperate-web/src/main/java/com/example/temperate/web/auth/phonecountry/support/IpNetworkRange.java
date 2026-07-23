package com.example.temperate.web.auth.phonecountry.support;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * IPv4/IPv6 CIDR 网段的解析与成员判断工具。
 *
 * <p>用途：为可信代理白名单和非公网保留网段提供无 DNS 名称解析的地址字面量校验与前缀匹配。</p>
 *
 * <p>安全原理：IPv4 手工校验每个字节，IPv6 先限制为字面量字符集；不接受主机名，避免代理信任判断受 DNS
 * 解析结果影响。</p>
 */
public final class IpNetworkRange {

    private final byte[] networkAddress;
    private final int prefixLength;

    private IpNetworkRange(byte[] networkAddress, int prefixLength) {
        this.networkAddress = networkAddress.clone();
        this.prefixLength = prefixLength;
    }

    public static Optional<IpNetworkRange> parse(String cidr) {
        if (cidr == null || cidr.isBlank()) {
            return Optional.empty();
        }
        String normalized = cidr.trim();
        int separator = normalized.indexOf('/');
        if (separator <= 0 || separator != normalized.lastIndexOf('/')) {
            return Optional.empty();
        }
        Optional<InetAddress> address = parseAddressLiteral(normalized.substring(0, separator));
        if (address.isEmpty()) {
            return Optional.empty();
        }
        try {
            int prefix = Integer.parseInt(normalized.substring(separator + 1));
            int maximumPrefix = address.get().getAddress().length * Byte.SIZE;
            if (prefix < 0 || prefix > maximumPrefix) {
                return Optional.empty();
            }
            return Optional.of(new IpNetworkRange(address.get().getAddress(), prefix));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<InetAddress> parseAddressLiteral(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim();
        // 冒号仅进入受限 IPv6 字面量分支，其余值必须是四段十进制 IPv4。
        if (normalized.indexOf(':') >= 0) {
            return parseIpv6Literal(normalized);
        }
        return parseIpv4Literal(normalized);
    }

    public boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != networkAddress.length) {
            return false;
        }
        int completeBytes = prefixLength / Byte.SIZE;
        int remainingBits = prefixLength % Byte.SIZE;
        for (int index = 0; index < completeBytes; index++) {
            if (candidate[index] != networkAddress[index]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (Byte.SIZE - remainingBits);
        return (candidate[completeBytes] & mask) == (networkAddress[completeBytes] & mask);
    }

    private static Optional<InetAddress> parseIpv4Literal(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        byte[] address = new byte[4];
        try {
            for (int index = 0; index < parts.length; index++) {
                if (parts[index].isEmpty() || !parts[index].matches("^[0-9]{1,3}$")) {
                    return Optional.empty();
                }
                int octet = Integer.parseInt(parts[index]);
                if (octet > 255) {
                    return Optional.empty();
                }
                address[index] = (byte) octet;
            }
            return Optional.of(InetAddress.getByAddress(address));
        } catch (NumberFormatException | UnknownHostException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<InetAddress> parseIpv6Literal(String value) {
        if (!value.matches("^[0-9A-Fa-f:.]+$")) {
            return Optional.empty();
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            return address instanceof Inet6Address ? Optional.of(address) : Optional.empty();
        } catch (UnknownHostException ignored) {
            return Optional.empty();
        }
    }
}
