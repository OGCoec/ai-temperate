package com.example.temperate.common.net.ip;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 将数字形式的 IPv4/IPv6 字面量转换为可比较、可展示并可参与 HMAC 的稳定二进制身份。
 *
 * <p>该类型不执行 DNS 查询，不负责判断地址是否属于公网；调用方应在代理信任或公网策略边界单独完成可用性判断。
 * IPv4-Mapped IPv6 会归一为 IPv4，避免同一网络端点产生两套风险身份。</p>
 */
public final class IpAddressIdentity {

    private static final byte IPV4_MARKER = 0x04;
    private static final byte IPV6_MARKER = 0x06;

    private final AddressFamily family;
    private final byte[] addressBytes;
    private final String canonicalText;

    private IpAddressIdentity(AddressFamily family, byte[] addressBytes) {
        this.family = Objects.requireNonNull(family);
        this.addressBytes = addressBytes.clone();
        this.canonicalText = family == AddressFamily.IPV4
                ? canonicalIpv4(this.addressBytes)
                : canonicalIpv6(this.addressBytes);
    }

    /**
     * 解析数字 IP 字面量；非法格式、主机名和带 Zone ID 的 IPv6 会被拒绝。
     */
    public static IpAddressIdentity parse(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        String candidate = value.trim();
        if (candidate.indexOf('%') >= 0) {
            throw invalid();
        }
        byte[] bytes = candidate.indexOf(':') >= 0
                ? parseIpv6(candidate)
                : parseIpv4(candidate);
        return fromBytes(bytes);
    }

    /**
     * 尝试解析数字 IP 字面量，供需要使用 Optional 表达无效输入的边界调用。
     */
    public static Optional<IpAddressIdentity> tryParse(String value) {
        try {
            return Optional.of(parse(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * 从已经由可信边界获得的 JDK 地址对象建立统一身份。
     */
    public static IpAddressIdentity fromAddress(InetAddress address) {
        if (address == null) {
            throw invalid();
        }
        return fromBytes(address.getAddress());
    }

    public AddressFamily family() {
        return family;
    }

    public byte[] addressBytes() {
        return addressBytes.clone();
    }

    public String canonicalText() {
        return canonicalText;
    }

    /**
     * 返回“地址族标识 + 网络序地址字节”，确保 HMAC 不依赖任何文本格式。
     */
    public byte[] hmacPayload() {
        byte[] payload = new byte[addressBytes.length + 1];
        payload[0] = family == AddressFamily.IPV4 ? IPV4_MARKER : IPV6_MARKER;
        System.arraycopy(addressBytes, 0, payload, 1, addressBytes.length);
        return payload;
    }

    public InetAddress toInetAddress() {
        try {
            return InetAddress.getByAddress(addressBytes);
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("Validated IP bytes cannot be reconstructed.", exception);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof IpAddressIdentity identity
                && family == identity.family
                && Arrays.equals(addressBytes, identity.addressBytes);
    }

    @Override
    public int hashCode() {
        return 31 * family.hashCode() + Arrays.hashCode(addressBytes);
    }

    @Override
    public String toString() {
        return "IpAddressIdentity[family=" + family + ", redacted]";
    }

    private static IpAddressIdentity fromBytes(byte[] source) {
        if (source == null) {
            throw invalid();
        }
        byte[] bytes = source.clone();
        if (bytes.length == 4) {
            return new IpAddressIdentity(AddressFamily.IPV4, bytes);
        }
        if (bytes.length != 16) {
            throw invalid();
        }
        // IPv4-Mapped IPv6 与其 IPv4 文本必须共享身份，避免绕过按 IP 聚合的风险状态。
        if (isIpv4Mapped(bytes)) {
            return new IpAddressIdentity(
                    AddressFamily.IPV4,
                    Arrays.copyOfRange(bytes, 12, 16));
        }
        return new IpAddressIdentity(AddressFamily.IPV6, bytes);
    }

    private static byte[] parseIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            throw invalid();
        }
        byte[] bytes = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty()
                    || part.length() > 3
                    || (part.length() > 1 && part.charAt(0) == '0')
                    || !part.chars().allMatch(character -> character >= '0'
                            && character <= '9')) {
                throw invalid();
            }
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException exception) {
                throw invalid();
            }
            if (octet > 255) {
                throw invalid();
            }
            bytes[index] = (byte) octet;
        }
        return bytes;
    }

    private static byte[] parseIpv6(String value) {
        if (value.length() > 45 || !value.matches("^[0-9A-Fa-f:.]+$")) {
            throw invalid();
        }
        int compression = value.indexOf("::");
        if (compression >= 0 && compression != value.lastIndexOf("::")) {
            throw invalid();
        }

        List<Integer> groups;
        if (compression >= 0) {
            String leftText = value.substring(0, compression);
            String rightText = value.substring(compression + 2);
            // IPv4 文本只能位于整个 IPv6 地址末尾；位于压缩段左侧会让地址含义不唯一。
            List<Integer> left = parseIpv6Side(leftText, false);
            List<Integer> right = parseIpv6Side(rightText, true);
            int missing = 8 - left.size() - right.size();
            if (missing < 1) {
                throw invalid();
            }
            groups = new ArrayList<>(8);
            groups.addAll(left);
            for (int index = 0; index < missing; index++) {
                groups.add(0);
            }
            groups.addAll(right);
        } else {
            groups = parseIpv6Side(value, true);
            if (groups.size() != 8) {
                throw invalid();
            }
        }

        byte[] bytes = new byte[16];
        for (int index = 0; index < groups.size(); index++) {
            int group = groups.get(index);
            bytes[index * 2] = (byte) (group >>> 8);
            bytes[index * 2 + 1] = (byte) group;
        }
        return bytes;
    }

    private static List<Integer> parseIpv6Side(
            String side,
            boolean allowTrailingIpv4) {
        if (side.isEmpty()) {
            return List.of();
        }
        String[] parts = side.split(":", -1);
        List<Integer> groups = new ArrayList<>(parts.length);
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty()) {
                throw invalid();
            }
            if (part.indexOf('.') >= 0) {
                if (!allowTrailingIpv4 || index != parts.length - 1) {
                    throw invalid();
                }
                byte[] ipv4 = parseIpv4(part);
                groups.add((Byte.toUnsignedInt(ipv4[0]) << 8)
                        | Byte.toUnsignedInt(ipv4[1]));
                groups.add((Byte.toUnsignedInt(ipv4[2]) << 8)
                        | Byte.toUnsignedInt(ipv4[3]));
                continue;
            }
            if (part.length() > 4 || !part.matches("^[0-9A-Fa-f]{1,4}$")) {
                throw invalid();
            }
            groups.add(Integer.parseInt(part, 16));
        }
        return groups;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    private static String canonicalIpv4(byte[] bytes) {
        return Byte.toUnsignedInt(bytes[0])
                + "."
                + Byte.toUnsignedInt(bytes[1])
                + "."
                + Byte.toUnsignedInt(bytes[2])
                + "."
                + Byte.toUnsignedInt(bytes[3]);
    }

    private static String canonicalIpv6(byte[] bytes) {
        int[] groups = new int[8];
        for (int index = 0; index < groups.length; index++) {
            groups[index] = (Byte.toUnsignedInt(bytes[index * 2]) << 8)
                    | Byte.toUnsignedInt(bytes[index * 2 + 1]);
        }
        int bestStart = -1;
        int bestLength = 0;
        for (int index = 0; index < groups.length;) {
            if (groups[index] != 0) {
                index++;
                continue;
            }
            int end = index;
            while (end < groups.length && groups[end] == 0) {
                end++;
            }
            int length = end - index;
            if (length >= 2 && length > bestLength) {
                bestStart = index;
                bestLength = length;
            }
            index = end;
        }
        if (bestStart < 0) {
            return joinIpv6Groups(groups, 0, groups.length);
        }
        return joinIpv6Groups(groups, 0, bestStart)
                + "::"
                + joinIpv6Groups(groups, bestStart + bestLength, groups.length);
    }

    private static String joinIpv6Groups(int[] groups, int start, int end) {
        StringBuilder result = new StringBuilder();
        for (int index = start; index < end; index++) {
            if (!result.isEmpty()) {
                result.append(':');
            }
            result.append(Integer.toHexString(groups[index]));
        }
        return result.toString();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("IP address literal is invalid.");
    }

    /** IP 地址族及其固定二进制宽度。 */
    public enum AddressFamily {
        IPV4,
        IPV6
    }
}
