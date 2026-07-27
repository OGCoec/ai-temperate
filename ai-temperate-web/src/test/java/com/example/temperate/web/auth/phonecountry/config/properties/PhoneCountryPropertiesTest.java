package com.example.temperate.web.auth.phonecountry.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 验证电话国家建议功能开关、查询期限与可信代理 CIDR 配置约束的测试。
 */
class PhoneCountryPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void acceptsAnEmptyTrustedProxyConfiguration() {
        PhoneCountryProperties properties = properties(Duration.ofSeconds(8), "");

        assertThat(properties.isTrustedProxyRangesValid()).isTrue();
        assertThat(properties.trustedProxyRangeList()).isEmpty();
        assertThat(properties.isLookupTimeoutValid()).isTrue();
    }

    @Test
    void acceptsCommaSeparatedIpv4AndIpv6Cidrs() {
        PhoneCountryProperties properties = properties(
                Duration.ofSeconds(8),
                "10.0.0.0/8, 2001:db8::/32");

        assertThat(properties.isTrustedProxyRangesValid()).isTrue();
        assertThat(properties.trustedProxyRangeList())
                .containsExactly("10.0.0.0/8", "2001:db8::/32");
    }

    @Test
    void rejectsMalformedTrustedProxyCidrs() {
        PhoneCountryProperties properties = properties(
                Duration.ofSeconds(8),
                "10.0.0.0/99");

        assertThat(properties.isTrustedProxyRangesValid()).isFalse();
    }

    @Test
    void rejectsMissingZeroAndNegativeLookupTimeouts() {
        assertThat(properties(null, "").isLookupTimeoutValid()).isFalse();
        assertThat(properties(Duration.ZERO, "").isLookupTimeoutValid()).isFalse();
        assertThat(properties(Duration.ofMillis(-1), "").isLookupTimeoutValid()).isFalse();
        assertThat(properties(Duration.ofMillis(1), "").isLookupTimeoutValid()).isTrue();
    }

    @Test
    void bindsTheEightSecondDefaultFromApplicationYaml() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PhoneCountryProperties.class).lookupTimeout())
                    .isEqualTo(Duration.ofSeconds(8));
        });
    }

    @Test
    void allowsTheEnvironmentPlaceholderToOverrideTheLookupTimeout() {
        contextRunner
                .withPropertyValues("AUTH_PHONE_COUNTRY_LOOKUP_TIMEOUT=3s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PhoneCountryProperties.class).lookupTimeout())
                            .isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    void rejectsANonPositiveLookupTimeoutDuringConfigurationBinding() {
        contextRunner
                .withPropertyValues("AUTH_PHONE_COUNTRY_LOOKUP_TIMEOUT=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    private static PhoneCountryProperties properties(
            Duration lookupTimeout, String trustedProxyRanges) {
        return new PhoneCountryProperties(
                true,
                "country.bin",
                lookupTimeout,
                trustedProxyRanges);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PhoneCountryProperties.class)
    static class TestConfiguration {
    }
}
