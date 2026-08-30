package com.example.temperate.web.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * 该测试是来锁定本地与 BAR 两阶段均只使用十六个明确批准账号，并校验 Provider 和模拟器隔离边界。
 */
final class MembershipPaymentLoadtestProfileYamlTest {

    private static final List<String> REALTIME_APPROVED_USER_IDS = List.of(
            "72659006262480896",
            "73014701344296960",
            "74891801495998464",
            "76721355290185728",
            "84736921162616832",
            "84739559597936640",
            "84742296792338432",
            "84745417706835968",
            "84746552547086336",
            "84753114204344320",
            "84754367089086464",
            "84755204414771200",
            "84758509811535872",
            "84758866549673984",
            "84759380653903872",
            "84760794662834176");

    @Test
    void realtimeProfileContainsExactlySixteenApprovedUsers() throws Exception {
        PropertySourcesPropertyResolver resolver = resolver("application-loadtest-realtime.yml");

        assertLocalDefaults(resolver);
        assertThat(resolver.getProperty(
                "app.membership-payment.observability.detail-log-enabled", Boolean.class))
                .isFalse();
        assertThat(resolver.getProperty(
                "app.membership-payment.observability.sample-rate", Double.class))
                .isZero();
        assertThat(resolver.getProperty(
                "app.membership-payment.observability.force-log-operations"))
                .isEqualTo("ORDER_CREATE,PAYMENT_ATTEMPT");
        assertThat(resolver.getProperty(
                "app.membership-payment.boundary-loadtest.enabled", Boolean.class))
                .isFalse();
        assertThat(resolver.getProperty("server.tomcat.accept-count", Integer.class))
                .isEqualTo(256);
        assertThat(resolver.getProperty("server.tomcat.max-connections", Integer.class))
                .isEqualTo(256);
        assertThat(resolver.getProperty("server.tomcat.threads.max", Integer.class))
                .isEqualTo(256);
        assertThat(resolver.getProperty("spring.rabbitmq.cache.channel.size", Integer.class))
                .isEqualTo(256);
        assertThat(resolver.getProperty("spring.rabbitmq.cache.channel.checkout-timeout"))
                .isEqualTo("30s");
        assertThat(resolver.getProperty("spring.rabbitmq.requested-channel-max", Integer.class))
                .isEqualTo(512);
        assertThat(resolver.getProperty(
                "app.membership-payment.redis-write.batch-size", Integer.class))
                .isEqualTo(64);
        assertThat(resolver.getProperty(
                "app.membership-payment.redis-write.lane-count", Integer.class))
                .isEqualTo(6);
        assertThat(resolver.getProperty(
                "app.membership-payment.redis-write.maximum-inflight", Integer.class))
                .isEqualTo(384);
        assertThat(resolver.getProperty("app.membership-payment.redis-write.flush-window"))
                .isEqualTo("1ms");
        assertThat(resolver.getProperty(
                "app.membership-payment.warmup.enabled", Boolean.class))
                .isTrue();
        assertThat(resolver.getProperty(
                "management.metrics.distribution.percentiles."
                        + "hikaricp.connections.acquire"))
                .isEqualTo("0.95,0.99");
        assertThat(resolver.getProperty(
                "management.metrics.distribution.percentiles."
                        + "hikaricp.connections.usage"))
                .isEqualTo("0.95,0.99");
        assertThat(allowedUserIds(resolver)).containsExactlyElementsOf(REALTIME_APPROVED_USER_IDS);
    }

    @Test
    void productionProfileUsesTheSameSixLightweightPipelines() throws Exception {
        PropertySourcesPropertyResolver resolver = resolver("application.yml");

        assertThat(resolver.getProperty(
                "app.membership-payment.redis-write.batch-size", Integer.class))
                .isEqualTo(64);
        assertThat(resolver.getProperty(
                "app.membership-payment.redis-write.lane-count", Integer.class))
                .isEqualTo(6);
        assertThat(resolver.getProperty(
                "app.membership-payment.redis-write.maximum-inflight", Integer.class))
                .isEqualTo(384);
    }

    @Test
    void fastProfileKeepsItsOriginalFourUserIsolation() throws Exception {
        PropertySourcesPropertyResolver resolver = resolver("application-loadtest-fast.yml");

        assertLocalDefaults(resolver);
        assertThat(allowedUserIds(resolver)).containsExactly(
                "73014701344296960",
                "72659006262480896",
                "76721355290185728",
                "74891801495998464");
    }

    @Test
    void barProfileUsesApprovedUsersAndNeverEnablesSimulator() throws Exception {
        PropertySourcesPropertyResolver resolver = resolver("application-loadtest-bar.yml");

        assertThat(resolver.getProperty("app.membership-payment.enabled", Boolean.class)).isTrue();
        assertThat(resolver.getProperty(
                "app.membership-payment.loadtest.enabled", Boolean.class)).isTrue();
        assertThat(resolver.getProperty("app.membership-payment.default-provider"))
                .isEqualTo("BAR");
        assertThat(resolver.getProperty(
                "app.membership-payment.bar.enabled", Boolean.class)).isTrue();
        assertThat(resolver.getProperty(
                "app.membership-payment.simulator.enabled", Boolean.class)).isFalse();
        assertThat(resolver.getProperty(
                "app.membership-payment.loadtest.inference-stub.enabled",
                Boolean.class)).isFalse();
        assertThat(resolver.getProperty(
                "app.membership-payment.loadtest.inference-stub.video-url"))
                .isEmpty();
        assertThat(allowedUserIds(resolver)).containsExactlyElementsOf(REALTIME_APPROVED_USER_IDS);
    }

    private static void assertLocalDefaults(PropertySourcesPropertyResolver resolver) {
        assertThat(resolver.getProperty("app.security.env")).isEqualTo("LOCAL");
        assertThat(resolver.getProperty("app.membership-payment.enabled", Boolean.class)).isTrue();
        assertThat(resolver.getProperty(
                "app.membership-payment.observability.enabled", Boolean.class)).isTrue();
        assertThat(resolver.getProperty(
                "app.membership-payment.observability.run-id")).isEqualTo("unavailable");
        assertThat(resolver.getProperty(
                "app.membership-payment.observability.include-public-order-id", Boolean.class))
                .isTrue();
        assertThat(resolver.getProperty(
                "app.membership-payment.loadtest.enabled", Boolean.class)).isTrue();
        assertThat(resolver.getProperty("app.membership-payment.simulator.pid"))
                .isEqualTo("loadtest-merchant");
        assertThat(resolver.getProperty("app.membership-payment.simulator.callback-key"))
                .isEqualTo("membership-loadtest-callback-key-v1-local");
    }

    private static List<String> allowedUserIds(PropertySourcesPropertyResolver resolver) {
        java.util.ArrayList<String> userIds = new java.util.ArrayList<>();
        for (int index = 0; ; index++) {
            String value = resolver.getProperty(
                    "app.membership-payment.loadtest.allowed-user-ids[" + index + "]");
            if (value == null) {
                return List.copyOf(userIds);
            }
            userIds.add(value);
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
