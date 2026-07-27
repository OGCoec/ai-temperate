package com.example.temperate.web.auth.phonecountry.config.properties;

import com.example.temperate.web.auth.phonecountry.support.IpNetworkRange;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 电话号码国家建议与可信代理网段的配置绑定。
 *
 * <p>用途：配置 IP2Location 数据文件开关、路径、单次查询期限及可以提供转发头的代理 CIDR 列表。</p>
 *
 * <p>安全原理：查询期限必须为正数，可信代理网段必须在启动期解析为合法 IPv4/IPv6 CIDR，
 * 防止错误配置造成无限等待或扩大可伪造转发头的信任范围。</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.phone-country")
public record PhoneCountryProperties(
        @NotNull Boolean enabled,
        @NotBlank String binPath,
        @NotNull Duration lookupTimeout,
        @NotNull String trustedProxyRanges) {

    public List<String> trustedProxyRangeList() {
        if (trustedProxyRanges == null || trustedProxyRanges.isBlank()) {
            return List.of();
        }
        return Arrays.stream(trustedProxyRanges.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    @AssertTrue(message = "Trusted proxy ranges must be valid IPv4 or IPv6 CIDR values")
    public boolean isTrustedProxyRangesValid() {
        return trustedProxyRangeList().stream()
                .allMatch(value -> IpNetworkRange.parse(value).isPresent());
    }

    @AssertTrue(message = "Phone country lookup timeout must be positive")
    public boolean isLookupTimeoutValid() {
        return lookupTimeout != null && !lookupTimeout.isZero() && !lookupTimeout.isNegative();
    }
}
