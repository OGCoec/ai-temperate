package com.example.temperate.service.admin.aimodel.discovery.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 验证 CLIProxyAPI 模型发现配置的默认安全边界和启用时密钥约束。
 */
final class CliProxyModelDiscoveryPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfiguration.class)
                    .withPropertyValues(
                            "app.admin.ai-model-discovery.cli-proxy.enabled=true",
                            "app.admin.ai-model-discovery.cli-proxy.base-url=http://127.0.0.1:8317",
                            "app.admin.ai-model-discovery.cli-proxy.api-key=test-cli-proxy-key",
                            "app.admin.ai-model-discovery.cli-proxy.connect-timeout=2s",
                            "app.admin.ai-model-discovery.cli-proxy.read-timeout=5s",
                            "app.admin.ai-model-discovery.cli-proxy.max-models=500");

    @Test
    void bindsApprovedLoopbackAndResourceLimits() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CliProxyModelDiscoveryProperties.class);
            CliProxyModelDiscoveryProperties properties =
                    context.getBean(CliProxyModelDiscoveryProperties.class);

            assertThat(properties.enabled()).isTrue();
            assertThat(properties.baseUrl()).isEqualTo(URI.create("http://127.0.0.1:8317"));
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.maxModels()).isEqualTo(500);
            assertThat(properties.toString()).doesNotContain("test-cli-proxy-key");
        });
    }

    @Test
    void allowsMissingKeyOnlyWhileFeatureIsDisabled() {
        CliProxyModelDiscoveryProperties disabled = new CliProxyModelDiscoveryProperties(
                false,
                URI.create("http://127.0.0.1:8317"),
                "",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                500);
        CliProxyModelDiscoveryProperties enabled = new CliProxyModelDiscoveryProperties(
                true,
                URI.create("http://127.0.0.1:8317"),
                "",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                500);

        assertThat(disabled.isApiKeyValidForState()).isTrue();
        assertThat(enabled.isApiKeyValidForState()).isFalse();
    }

    @Test
    void enabledBindingFailsWhenApiKeyIsMissing() {
        contextRunner
                .withPropertyValues(
                        "app.admin.ai-model-discovery.cli-proxy.api-key=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindingRejectsInvalidTimeoutAndModelCountBoundaries() {
        contextRunner
                .withPropertyValues(
                        "app.admin.ai-model-discovery.cli-proxy.connect-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues(
                        "app.admin.ai-model-discovery.cli-proxy.max-models=501")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 仅启用配置属性绑定，不创建 RestClient，也不会连接本机 CLIProxyAPI。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CliProxyModelDiscoveryProperties.class)
    static class TestConfiguration {
    }
}
