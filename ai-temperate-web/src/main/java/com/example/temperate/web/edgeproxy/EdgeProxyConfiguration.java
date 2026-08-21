package com.example.temperate.web.edgeproxy;

import java.time.Clock;
import com.example.temperate.web.user.membership.payment.loadtest.MembershipPaymentLoadtestRequestPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 装配边缘签名属性、验签器和过滤器，并审计生产环境是否完成 REQUIRED 安全收口。
 *
 * <p>过滤器由三条 Spring Security 链显式放在 CORS 之前，确保管理员、普通 H5 和 Android
 * 请求只经过一次相同的边缘边界。生产切换窗口仍可使用 OPTIONAL，但启动期会输出 ERROR
 * 级稳定分类，避免兼容模式被误认为已经完成安全收口。</p>
 */
@Configuration
@EnableConfigurationProperties(EdgeProxyProperties.class)
public class EdgeProxyConfiguration {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(EdgeProxyConfiguration.class);

    /**
     * 在 Spring 启动期审计生产边缘模式，给受控切换窗口保留启动能力，同时显式暴露未收口状态。
     *
     * <p>这里不记录密钥或外部输入；部署完成验收后必须把模式切到 REQUIRED，ERROR 日志才会消失。</p>
     */
    @Bean
    InitializingBean edgeProxyProductionModeAudit(
            EdgeProxyProperties edgeProxyProperties,
            Environment environment) {
        return () -> {
            if ("PROD".equalsIgnoreCase(
                            environment.getProperty("app.security.env", "PROD"))
                    && edgeProxyProperties.mode() != EdgeProxyMode.REQUIRED) {
                LOGGER.error(
                        "security_edge_proxy_mode_not_required environment=PROD mode={}",
                        edgeProxyProperties.mode());
            }
        };
    }

    @Bean
    EdgeProxySignatureVerifier edgeProxySignatureVerifier(
            EdgeProxyProperties properties) {
        return new EdgeProxySignatureVerifier(properties, Clock.systemUTC());
    }

    @Bean
    EdgeProxySignatureFilter edgeProxySignatureFilter(
            EdgeProxyProperties properties,
            EdgeProxySignatureVerifier verifier,
            MembershipPaymentLoadtestRequestPolicy loadtestRequestPolicy) {
        return new EdgeProxySignatureFilter(properties, verifier, loadtestRequestPolicy);
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
