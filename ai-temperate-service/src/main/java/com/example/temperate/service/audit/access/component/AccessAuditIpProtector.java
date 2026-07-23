package com.example.temperate.service.audit.access.component;

import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.audit.access.domain.ProtectedClientIp;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 将请求内短暂存在的规范 IP 转换为 IPv4 /24 或 IPv6 /48 前缀及独立域 HMAC。
 *
 * <p>该组件不记录原始 IP；字面量校验先拒绝主机名，避免将不可信输入交给 DNS 解析。</p>
 */
public final class AccessAuditIpProtector {

    private static final String HMAC_DOMAIN = "access-ip:v1:";
    private static final Pattern ADDRESS_LITERAL = Pattern.compile("^[0-9A-Fa-f:.]+$");
    private static final String UNAVAILABLE = "unavailable";

    private final HmacSha256Identifier hmacIdentifier;

    public AccessAuditIpProtector(byte[] secret) {
        this.hmacIdentifier = new HmacSha256Identifier(secret);
    }

    public ProtectedClientIp protect(String canonicalClientIp) {
        InetAddress address = parseLiteral(canonicalClientIp);
        if (address instanceof Inet4Address) {
            byte[] bytes = address.getAddress();
            String prefix = "%d.%d.%d.0/24".formatted(
                    unsigned(bytes[0]), unsigned(bytes[1]), unsigned(bytes[2]));
            return protectedValue(4, prefix, address.getHostAddress());
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            String prefix = "%x:%x:%x::/48".formatted(
                    group(bytes, 0), group(bytes, 2), group(bytes, 4))
                    .toLowerCase(Locale.ROOT);
            return protectedValue(6, prefix, address.getHostAddress());
        }
        return protectedValue(0, UNAVAILABLE, UNAVAILABLE);
    }

    private ProtectedClientIp protectedValue(int family, String prefix, String canonicalValue) {
        // 固定域前缀防止同一密钥下不同标识类型发生跨用途关联；本功能仍要求使用独立密钥。
        String hmac = hmacIdentifier.identify(HMAC_DOMAIN + canonicalValue).value();
        return new ProtectedClientIp(family, prefix, hmac);
    }

    private static InetAddress parseLiteral(String value) {
        if (value == null || value.isBlank() || value.contains("%")
                || (!value.contains(".") && !value.contains(":"))
                || !ADDRESS_LITERAL.matcher(value).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static int group(byte[] value, int offset) {
        return (unsigned(value[offset]) << 8) | unsigned(value[offset + 1]);
    }
}
