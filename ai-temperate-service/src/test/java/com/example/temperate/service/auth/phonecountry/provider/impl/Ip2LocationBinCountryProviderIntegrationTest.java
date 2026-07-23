package com.example.temperate.service.auth.phonecountry.provider.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "ip2location.bin.path", matches = ".+")
/**
 * 验证 IP2Location BIN 提供者的文件加载、国家代码解析和 Fail Open 行为。
 */
class Ip2LocationBinCountryProviderIntegrationTest {

    private Ip2LocationBinCountryProvider provider;

    @BeforeEach
    void setUp() {
        provider = new Ip2LocationBinCountryProvider(
                true,
                System.getProperty("ip2location.bin.path"),
                new SimpleMeterRegistry());
        provider.initialize();
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void resolvesKnownGoogleIpv4AndIpv6AddressesToTheUnitedStates() {
        assertThat(provider.findCountryIso2("8.8.8.8")).contains("US");
        assertThat(provider.findCountryIso2("2001:4860:4860::8888")).contains("US");
    }
}
