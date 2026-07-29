package com.example.temperate.service.admin.aimodel.discovery.config;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings.Redirects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * 装配 CLIProxyAPI 专用同步 RestClient，并把地址、超时、重放策略和 Bearer 密钥固定在服务端边界。
 *
 * <p>该配置不会在启动时连接上游；客户端不接受请求级地址或密钥覆盖。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CliProxyModelDiscoveryProperties.class)
public class CliProxyModelDiscoveryConfiguration {

    @Bean
    @Qualifier("cliProxyModelRestClient")
    public RestClient cliProxyModelRestClient(
            RestClient.Builder builder,
            CliProxyModelDiscoveryProperties properties) {
        return customizeCliProxyModelRestClientBuilder(builder, properties).build();
    }

    /**
     * 将固定上游地址、超时和可选 Bearer Header 应用到专用 Builder，便于契约测试在构建前接管传输层。
     */
    public RestClient.Builder customizeCliProxyModelRestClientBuilder(
            RestClient.Builder builder,
            CliProxyModelDiscoveryProperties properties) {
        Objects.requireNonNull(builder);
        Objects.requireNonNull(properties);
        ClientHttpRequestFactorySettings settings =
                ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(properties.connectTimeout())
                        .withReadTimeout(properties.readTimeout())
                        // 禁止携带普通代理密钥跟随重定向，固定模型发现只能命中配置的上游 Origin。
                        .withRedirects(Redirects.DONT_FOLLOW);
        RestClient.Builder configured = builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(ClientHttpRequestFactoryBuilder.httpComponents()
                        // 管理员通过刷新显式重试；底层客户端禁止擅自重放带认证头的 GET。
                        .withHttpClientCustomizer(
                                httpClient -> httpClient.disableAutomaticRetries())
                        .build(settings));
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            configured.defaultHeader(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + properties.apiKey().trim());
        }
        return configured;
    }
}
