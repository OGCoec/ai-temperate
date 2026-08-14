package com.example.temperate.web.user.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import com.example.temperate.service.user.apikey.config.ApiKeyProperties;

/**
 * 该契约测试是来锁定生产 YAML 默认启用 API Key 功能，同时保留环境变量显式关闭能力和紧邻中文安全说明。
 */
final class ApiKeyConfigurationContractTest {

    private static final String TEST_HMAC_SECRET_BASE64 =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> {
                // ApplicationContextInitializer 不允许抛出受检异常，使用 UncheckedIOException 包装 YAML 加载异常以保留错误传播。
                try {
                    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
                    List<org.springframework.core.env.PropertySource<?>> propertySources =
                            loader.load("application", new ClassPathResource("application.yml"));
                    propertySources.forEach(
                            propertySource -> context.getEnvironment().getPropertySources().addLast(propertySource));
                } catch (IOException e) {
                    throw new UncheckedIOException("加载测试 application.yml 失败", e);
                }
            })
            .withPropertyValues("API_KEY_HMAC_SECRET_BASE64=" + TEST_HMAC_SECRET_BASE64)
            .withUserConfiguration(ApiKeyBindingConfiguration.class);

    @Test
    void productionYamlEnablesApiKeysByDefaultWithAnAccurateAdjacentComment()
            throws IOException {
        String yaml = Files.readString(
                Path.of("src/main/resources/application.yml"),
                StandardCharsets.UTF_8);

        assertThat(yaml).contains(
                "    # 默认启用公开 API Key 管理与认证；启动前必须完成数据库迁移、固定 Secret 和 Bloom 重建准备。\n"
                        + "    enabled: ${API_KEY_ENABLED:true}");
    }

    @Test
    void productionYamlParsesAndBindsApiKeysAsEnabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ApiKeyProperties.class).isEnabled()).isTrue();
        });
    }

    @Test
    void environmentPropertyCanStillDisableApiKeysExplicitly() {
        contextRunner
                .withPropertyValues("API_KEY_ENABLED=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ApiKeyProperties.class).isEnabled()).isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ApiKeyProperties.class)
    static class ApiKeyBindingConfiguration {
    }
}
