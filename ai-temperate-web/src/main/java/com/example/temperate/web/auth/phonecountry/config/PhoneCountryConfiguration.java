package com.example.temperate.web.auth.phonecountry.config;

import com.example.temperate.service.auth.phonecountry.provider.impl.Ip2LocationBinCountryProvider;
import com.example.temperate.web.auth.phonecountry.config.properties.PhoneCountryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 电话号码国家建议能力的 Spring 装配配置。
 *
 * <p>用途：将配置绑定为 IP 国家解析器和服务端查询期限，并允许通过开关安全降级为无结果。</p>
 */
@Configuration
@EnableConfigurationProperties(PhoneCountryProperties.class)
public class PhoneCountryConfiguration {

    @Bean
    Ip2LocationBinCountryProvider ipCountryProvider(
            PhoneCountryProperties properties,
            MeterRegistry meterRegistry) {
        return new Ip2LocationBinCountryProvider(
                Boolean.TRUE.equals(properties.enabled()),
                properties.binPath(),
                meterRegistry);
    }

    @Bean("phoneCountryLookupTimeout")
    Duration phoneCountryLookupTimeout(PhoneCountryProperties properties) {
        return properties.lookupTimeout();
    }
}
