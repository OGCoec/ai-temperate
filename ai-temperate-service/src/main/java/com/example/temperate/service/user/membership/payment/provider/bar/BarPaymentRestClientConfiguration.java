package com.example.temperate.service.user.membership.payment.provider.bar;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings.Redirects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 该配置是来装配 BAR 专用同步 RestClient，并固定 HTTPS Origin、连接池、超时、禁重试和禁重定向边界。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "app.membership-payment.bar",
        name = "enabled",
        havingValue = "true")
public class BarPaymentRestClientConfiguration {

    @Bean
    @Qualifier("barPaymentRestClient")
    public RestClient barPaymentRestClient(
            RestClient.Builder builder,
            MembershipPaymentProperties properties) {
        MembershipPaymentProperties.Bar bar = Objects.requireNonNull(properties).bar();
        ClientHttpRequestFactorySettings settings =
                ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(bar.connectTimeout())
                        .withReadTimeout(bar.readTimeout())
                        .withRedirects(Redirects.DONT_FOLLOW);
        return builder
                .baseUrl(bar.baseUrl().toString())
                .requestFactory(ClientHttpRequestFactoryBuilder.httpComponents()
                        // 创建、关单和退款都由业务层显式幂等重试，底层不得暗中重放带签名请求。
                        .withHttpClientCustomizer(httpClient -> httpClient
                                .disableAutomaticRetries()
                                .disableRedirectHandling())
                        .build(settings))
                .build();
    }
}
