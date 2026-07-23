package com.example.temperate.web.auth.phonecountry.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 验证电话国家建议功能开关与可信代理 CIDR 配置约束的测试。
 */
class PhoneCountryPropertiesTest {

    @Test
    void acceptsAnEmptyTrustedProxyConfiguration() {
        PhoneCountryProperties properties = new PhoneCountryProperties(true, "country.bin", "");

        assertThat(properties.isTrustedProxyRangesValid()).isTrue();
        assertThat(properties.trustedProxyRangeList()).isEmpty();
    }

    @Test
    void acceptsCommaSeparatedIpv4AndIpv6Cidrs() {
        PhoneCountryProperties properties = new PhoneCountryProperties(
                true,
                "country.bin",
                "10.0.0.0/8, 2001:db8::/32");

        assertThat(properties.isTrustedProxyRangesValid()).isTrue();
        assertThat(properties.trustedProxyRangeList())
                .containsExactly("10.0.0.0/8", "2001:db8::/32");
    }

    @Test
    void rejectsMalformedTrustedProxyCidrs() {
        PhoneCountryProperties properties = new PhoneCountryProperties(
                true,
                "country.bin",
                "10.0.0.0/99");

        assertThat(properties.isTrustedProxyRangesValid()).isFalse();
    }
}
