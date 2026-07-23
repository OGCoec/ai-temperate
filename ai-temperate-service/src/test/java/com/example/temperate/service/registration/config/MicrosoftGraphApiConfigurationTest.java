package com.example.temperate.service.registration.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.registration.verification.delivery.util.microsoft.MicrosoftGraphApiMailUtil;
import com.example.temperate.service.registration.verification.delivery.util.microsoft.MicrosoftGraphApiProperties;
import com.example.temperate.service.registration.verification.delivery.util.microsoft.MicrosoftGraphOAuthTokenUtil;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 验证 Microsoft Graph 响应式组件无需发件地址即可注册，并正确处理新旧超时配置的优先级。
 *
 * <p>上下文测试只创建 WebClient 适配器，不订阅任何网络请求。</p>
 */
class MicrosoftGraphApiConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MicrosoftGraphApiConfiguration.class)
            .withBean(WebClient.Builder.class, WebClient::builder)
            .withPropertyValues(
                    "app.registration.microsoft-graph.oauth.client-id=client-id",
                    "app.registration.microsoft-graph.oauth.client-secret=client-secret",
                    "app.registration.microsoft-graph.oauth.refresh-token=refresh-token");

    @Test
    void registersWithoutSenderEmailAndUsesLegacyTimeoutAsFallback() {
        contextRunner
                .withPropertyValues(
                        "app.registration.microsoft-graph.request-timeout=7s")
                .run(context -> {
                    assertThat(context).hasSingleBean(MicrosoftGraphApiProperties.class);
                    assertThat(context).hasSingleBean(MicrosoftGraphOAuthTokenUtil.class);
                    assertThat(context).hasSingleBean(MicrosoftGraphApiMailUtil.class);
                    MicrosoftGraphApiProperties properties =
                            context.getBean(MicrosoftGraphApiProperties.class);
                    assertThat(properties.oauthTimeout()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(properties.sendTimeout()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(properties.sendUri())
                            .isEqualTo("https://graph.microsoft.com/v1.0/me/sendMail");
                });
    }

    @Test
    void newStageTimeoutsTakePriorityOverLegacyFallback() {
        contextRunner
                .withPropertyValues(
                        "app.registration.microsoft-graph.request-timeout=7s",
                        "app.registration.microsoft-graph.oauth-timeout=9s",
                        "app.registration.microsoft-graph.send-timeout=11s",
                        "app.registration.microsoft-graph.send-uri=https://graph.example.test/me/sendMail")
                .run(context -> {
                    MicrosoftGraphApiProperties properties =
                            context.getBean(MicrosoftGraphApiProperties.class);
                    assertThat(properties.oauthTimeout()).isEqualTo(Duration.ofSeconds(9));
                    assertThat(properties.sendTimeout()).isEqualTo(Duration.ofSeconds(11));
                    assertThat(properties.sendUri())
                            .isEqualTo("https://graph.example.test/me/sendMail");
                });
    }
}
