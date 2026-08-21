package com.example.temperate.web.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * 该测试是来锁定两个 loadtest Profile 自带四个既有账号和本机模拟回调默认值，避免 Runner 再要求人工填密钥。
 */
final class MembershipPaymentLoadtestProfileYamlTest {

    @Test
    void fastAndRealtimeProfilesContainTheSameLocalTestDefaults() throws Exception {
        for (String resource : List.of(
                "application-loadtest-fast.yml",
                "application-loadtest-realtime.yml")) {
            PropertySourcesPropertyResolver resolver = resolver(resource);
            assertThat(resolver.getProperty("app.security.env")).isEqualTo("LOCAL");
            assertThat(resolver.getProperty("app.membership-payment.enabled", Boolean.class))
                    .isTrue();
            assertThat(resolver.getProperty("app.membership-payment.loadtest.enabled", Boolean.class))
                    .isTrue();
            assertThat(resolver.getProperty(
                    "app.membership-payment.loadtest.allowed-user-ids[0]"))
                    .isEqualTo("73014701344296960");
            assertThat(resolver.getProperty(
                    "app.membership-payment.loadtest.allowed-user-ids[3]"))
                    .isEqualTo("74891801495998464");
            assertThat(resolver.getProperty("app.membership-payment.simulator.pid"))
                    .isEqualTo("loadtest-merchant");
            assertThat(resolver.getProperty("app.membership-payment.simulator.callback-key"))
                    .isEqualTo("membership-loadtest-callback-key-v1-local");
        }
    }

    private static PropertySourcesPropertyResolver resolver(String resource) throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load(resource, new ClassPathResource(resource))
                .forEach(sources::addLast);
        return new PropertySourcesPropertyResolver(sources);
    }
}
