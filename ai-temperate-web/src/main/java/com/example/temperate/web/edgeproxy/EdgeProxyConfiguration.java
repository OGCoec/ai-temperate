package com.example.temperate.web.edgeproxy;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配边缘签名属性、验签器和过滤器，并禁止 Servlet 容器重复自动注册过滤器。
 *
 * <p>过滤器由三条 Spring Security 链显式放在 CORS 之前，确保管理员、普通 H5 和 Android
 * 请求只经过一次相同的边缘边界。</p>
 */
@Configuration
@EnableConfigurationProperties(EdgeProxyProperties.class)
public class EdgeProxyConfiguration {

    @Bean
    EdgeProxySignatureVerifier edgeProxySignatureVerifier(
            EdgeProxyProperties properties) {
        return new EdgeProxySignatureVerifier(properties, Clock.systemUTC());
    }

    @Bean
    EdgeProxySignatureFilter edgeProxySignatureFilter(
            EdgeProxyProperties properties,
            EdgeProxySignatureVerifier verifier) {
        return new EdgeProxySignatureFilter(properties, verifier);
    }

    @Bean
    FilterRegistrationBean<EdgeProxySignatureFilter>
            edgeProxySignatureFilterRegistration(
                    EdgeProxySignatureFilter filter) {
        FilterRegistrationBean<EdgeProxySignatureFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
