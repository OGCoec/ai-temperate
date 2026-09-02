package com.example.temperate.service.user.membership.payment.provider.liuhao;

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

/** 该配置是来装配六号专用 HTTPS RestClient，禁止重定向和底层自动重放带签名请求。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "app.membership-payment.liuhao",
        name = "enabled",
        havingValue = "true")
public class LiuhaoPaymentRestClientConfiguration {

    @Bean
    @Qualifier("liuhaoPaymentRestClient")
    public RestClient liuhaoPaymentRestClient(
            RestClient.Builder builder,
            MembershipPaymentProperties properties) {
        MembershipPaymentProperties.Liuhao liuhao =
                Objects.requireNonNull(properties).liuhao();
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(liuhao.connectTimeout())
                .withReadTimeout(liuhao.readTimeout())
                .withRedirects(Redirects.DONT_FOLLOW);
        return builder
                .baseUrl(liuhao.baseUrl().toString())
                .requestFactory(ClientHttpRequestFactoryBuilder.httpComponents()
                        .withHttpClientCustomizer(httpClient -> httpClient
                                .disableAutomaticRetries()
                                .disableRedirectHandling())
                        .build(settings))
                .build();
    }
}
