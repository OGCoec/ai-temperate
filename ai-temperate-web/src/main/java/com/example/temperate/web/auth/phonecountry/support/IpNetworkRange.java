package com.example.temperate.web.auth.phonecountry.support;

import com.example.temperate.common.net.ip.IpAddressIdentity;
import java.net.InetAddress;
import java.util.Optional;

/**
 * IPv4/IPv6 CIDR 网段的解析与成员判断工具。
 *
 * <p>用途：为可信代理白名单和非公网保留网段提供无 DNS 名称解析的地址字面量校验与前缀匹配。</p>
 *
 * <p>安全原理：地址与候选值统一交给二进制 IP 身份模型，不接受主机名或 Zone ID，避免代理信任判断受 DNS
 * 解析结果及 IPv6 文本格式差异影响。</p>
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
        Optional<IpAddressIdentity> address =
                IpAddressIdentity.tryParse(normalized.substring(0, separator));
        if (address.isEmpty()) {
            return Optional.empty();
        }
        try {
            int prefix = Integer.parseInt(normalized.substring(separator + 1));
            int maximumPrefix = address.orElseThrow().addressBytes().length * Byte.SIZE;
            if (prefix < 0 || prefix > maximumPrefix) {
                return Optional.empty();
            }
            return Optional.of(new IpNetworkRange(
                    address.orElseThrow().addressBytes(), prefix));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<InetAddress> parseAddressLiteral(String value) {
        return IpAddressIdentity.tryParse(value).map(IpAddressIdentity::toInetAddress);
    }

    public boolean contains(InetAddress address) {
        byte[] candidate;
        try {
            candidate = IpAddressIdentity.fromAddress(address).addressBytes();
        } catch (IllegalArgumentException exception) {
            return false;
        }
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

}
