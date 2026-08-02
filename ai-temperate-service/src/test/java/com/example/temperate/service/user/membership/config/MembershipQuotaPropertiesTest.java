package com.example.temperate.service.user.membership.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 验证会员额度配置在 Spring 绑定阶段完整覆盖七档套餐并拒绝缺档、负数和溢出值。
 */
class MembershipQuotaPropertiesTest {

    @Test
    void bindsEveryTierAndSevenDayPeriod() {
        quotaContext(defaultLimits()).run(context -> {
            assertThat(context).hasNotFailed();
            MembershipQuotaProperties properties =
                    context.getBean(MembershipQuotaProperties.class);
            assertThat(properties.period()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.limits())
                    .containsEntry(MembershipTier.FREE, 5_000L)
                    .containsEntry(MembershipTier.MAX, 5_000_000L)
                    .hasSize(MembershipTier.values().length);
        });
    }

    @Test
    void rejectsMissingNegativeAndOverflowingTierValues() {
        Map<MembershipTier, String> missingTier = defaultLimits();
        missingTier.remove(MembershipTier.TEAM);
        quotaContext(missingTier).run(context -> assertThat(context).hasFailed());

        Map<MembershipTier, String> negativeLimit = defaultLimits();
        negativeLimit.put(MembershipTier.FREE, "-1");
        quotaContext(negativeLimit).run(context -> assertThat(context).hasFailed());

        Map<MembershipTier, String> overflowingLimit = defaultLimits();
        overflowingLimit.put(MembershipTier.PRO, "9223372036854775808");
        quotaContext(overflowingLimit).run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner quotaContext(Map<MembershipTier, String> limits) {
        List<String> properties = new ArrayList<>();
        properties.add("app.membership-quota.period=P7D");
        limits.forEach((tier, totalMinor) -> properties.add(
                "app.membership-quota.limits." + tier.name() + "=" + totalMinor));
        return new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(properties.toArray(String[]::new));
    }

    private Map<MembershipTier, String> defaultLimits() {
        Map<MembershipTier, String> limits = new EnumMap<>(MembershipTier.class);
        limits.put(MembershipTier.FREE, "5000");
        limits.put(MembershipTier.GO, "50000");
        limits.put(MembershipTier.EDU, "80000");
        limits.put(MembershipTier.TEAM, "180000");
        limits.put(MembershipTier.PLUS, "200000");
        limits.put(MembershipTier.PRO, "1000000");
        limits.put(MembershipTier.MAX, "5000000");
        return limits;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MembershipQuotaProperties.class)
    static class TestConfiguration {
    }
}
