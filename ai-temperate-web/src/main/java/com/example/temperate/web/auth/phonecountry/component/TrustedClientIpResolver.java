package com.example.temperate.web.auth.phonecountry.component;

import com.example.temperate.web.auth.phonecountry.config.properties.PhoneCountryProperties;
import com.example.temperate.web.auth.phonecountry.support.IpNetworkRange;
import com.example.temperate.web.auth.phonecountry.support.PublicIpAddressPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 在可信反向代理边界内解析真实客户端 IP 的组件。
 *
 * <p>用途：只有连接来源属于配置的可信代理网段时，才按 {@code CF-Connecting-IP}、
 * {@code X-Forwarded-For}、{@code X-Real-IP} 的顺序解析转发头；否则只考虑连接对端地址。</p>
 *
 * <p>安全原理：所有候选值都必须是公网地址；权威头存在但异常时立即失败，可信转发链从右向左确定安全边界，
 * 禁止回退到回环、私网或攻击者可能伪造的更左侧地址。</p>
 */
@Component
public final class TrustedClientIpResolver {

    private static final int MAX_FORWARDED_ADDRESSES = 20;

    private final List<IpNetworkRange> trustedProxyRanges;

    public TrustedClientIpResolver(PhoneCountryProperties properties) {
        this.trustedProxyRanges = properties.trustedProxyRangeList().stream()
                .map(value -> IpNetworkRange.parse(value)
                        .orElseThrow(() -> new IllegalArgumentException("Invalid trusted proxy CIDR")))
                .toList();
    }

    public Optional<String> resolve(HttpServletRequest request) {
        Optional<InetAddress> remoteAddress = IpNetworkRange.parseAddressLiteral(request.getRemoteAddr());
        if (remoteAddress.isEmpty()) {
            return Optional.empty();
        }
        // 直连来源的转发头完全不可信；只有连接对端本身是公网地址时才允许进入国家库。
        if (!isTrustedProxy(remoteAddress.get())) {
            return canonicalPublic(remoteAddress.get());
        }

        String cloudflareIp = request.getHeader("CF-Connecting-IP");
        if (cloudflareIp != null) {
            // Cloudflare 头是当前隧道的权威来源，存在但异常时不得降级到客户端可伪造的兼容头。
            return parsePublicAddress(cloudflareIp);
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null) {
            return resolveForwardedChain(forwardedFor);
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null) {
            return parsePublicAddress(realIp);
        }
        // 可信代理缺少真实客户端头时不能把代理自身地址误当客户端，也不能查询回环或内网地址。
        return Optional.empty();
    }

    private Optional<String> resolveForwardedChain(String forwardedFor) {
        String[] values = forwardedFor.split(",", -1);
        if (values.length == 0 || values.length > MAX_FORWARDED_ADDRESSES) {
            return Optional.empty();
        }
        List<InetAddress> addresses = new ArrayList<>(values.length);
        for (String value : values) {
            Optional<InetAddress> address = IpNetworkRange.parseAddressLiteral(value);
            if (address.isEmpty()) {
                return Optional.empty();
            }
            addresses.add(address.get());
        }
        // 最右侧最接近本服务：向左找到第一个非可信代理地址，才是可采信的客户端候选值。
        for (int index = addresses.size() - 1; index >= 0; index--) {
            InetAddress candidate = addresses.get(index);
            if (!isTrustedProxy(candidate)) {
                // 第一个非可信地址就是安全边界；若它不是公网地址，继续向左会采信攻击者可控内容。
                return canonicalPublic(candidate);
            }
        }
        return Optional.empty();
    }

    private boolean isTrustedProxy(InetAddress address) {
        return trustedProxyRanges.stream().anyMatch(range -> range.contains(address));
    }

    private static Optional<String> parsePublicAddress(String value) {
        return IpNetworkRange.parseAddressLiteral(value)
                .flatMap(TrustedClientIpResolver::canonicalPublic);
    }

    private static Optional<String> canonicalPublic(InetAddress address) {
        if (!PublicIpAddressPolicy.isPublic(address)) {
            return Optional.empty();
        }
        String value = address.getHostAddress();
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
