package com.example.temperate.web.risk;

import java.util.Objects;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 将网络风险拦截器注册为所有 MVC Interceptor 中的最高优先级并排除自身引导与 Challenge 闭环路径。
 */
@Configuration
public class NetworkRiskWebMvcConfiguration implements WebMvcConfigurer {

    private static final String BAR_PAYMENT_CALLBACK_PATH =
            "/api/payment/bar/notify";

    private final NetworkRiskInterceptor interceptor;

    public NetworkRiskWebMvcConfiguration(NetworkRiskInterceptor interceptor) {
        this.interceptor = Objects.requireNonNull(interceptor);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .order(Ordered.HIGHEST_PRECEDENCE)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/health",
                        "/api/_edge/cookie-scope",
                        "/api/admin/_edge/cookie-scope",
                        "/api/_edge/pre-auth",
                        "/api/admin/_edge/pre-auth",
                        "/api/_edge/risk-challenge",
                        "/api/admin/_edge/risk-challenge",
                        // 服务器回调无法提供浏览器网络风险上下文，来源真实性由回调 HMAC 与 BAR 主动查询保证。
                        BAR_PAYMENT_CALLBACK_PATH,
                        // OAuth Provider 顶层导航不能附加应用请求头，只由一次性 state/PKCE/nonce/握手 Cookie 保护。
                        "/api/auth/oauth2/authorization/**",
                        "/api/auth/oauth2/code/**",
                        /*
                         * WebView 子资源请求不会复用首个页面请求的自定义请求头，因此仅放行不含凭据和运行时数据的
                         * 精确 CSS/JavaScript 资源路径；受控 HTML 入口仍按原规则执行参数与风险校验。
                         */
                        "/api/auth/turnstile/page.css",
                        "/api/auth/turnstile/page.js",
                        "/api/admin/auth/hcaptcha/page.css",
                        "/api/admin/auth/hcaptcha/page.js");
    }
}
