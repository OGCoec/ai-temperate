package com.example.temperate.web.admin.security;

import java.util.Objects;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 按固定优先级注册管理员配置状态与会话认证业务门，使其在网络风险和 WebRTC 校验完成后运行。
 *
 * <p>这里只迁移管理员业务校验；Edge 签名、CORS 与 CSRF 仍属于 Servlet/Spring Security 过滤器链，
 * 不在 MVC 注册表中重复执行。</p>
 */
@Configuration
public class AdminWebMvcConfiguration implements WebMvcConfigurer {

    private static final String[] ADMIN_PATHS = {
        "/api/admin",
        "/api/admin/**"
    };

    private static final String[] PUBLIC_ADMIN_PATHS = {
        "/api/admin/auth/state",
        "/api/admin/_edge/pre-auth",
        "/api/admin/_edge/risk-challenge",
        "/api/admin/_edge/webrtc/start",
        "/api/admin/_edge/webrtc/report",
        "/api/admin/auth/phone-country",
        "/api/admin/auth/hcaptcha/config",
        "/api/admin/auth/hcaptcha/page",
        "/api/admin/auth/hcaptcha/page.css",
        "/api/admin/auth/hcaptcha/page.js"
    };

    private static final String[] PUBLIC_AUTHENTICATION_PATHS = {
        "/api/admin/auth/register",
        "/api/admin/auth/register/**",
        "/api/admin/auth/login",
        "/api/admin/auth/login/**"
    };

    private final AdminConfigurationStateInterceptor configurationStateInterceptor;
    private final AdminSessionAuthenticationInterceptor sessionAuthenticationInterceptor;

    public AdminWebMvcConfiguration(
            AdminConfigurationStateInterceptor configurationStateInterceptor,
            AdminSessionAuthenticationInterceptor sessionAuthenticationInterceptor) {
        this.configurationStateInterceptor =
                Objects.requireNonNull(configurationStateInterceptor);
        this.sessionAuthenticationInterceptor =
                Objects.requireNonNull(sessionAuthenticationInterceptor);
    }

    /**
     * 固定管理员业务门的顺序，禁止依赖配置类扫描顺序或 Bean 名称推断安全优先级。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(configurationStateInterceptor)
                .order(Ordered.HIGHEST_PRECEDENCE + 2)
                .addPathPatterns(ADMIN_PATHS)
                .excludePathPatterns(PUBLIC_ADMIN_PATHS);

        registry.addInterceptor(sessionAuthenticationInterceptor)
                .order(Ordered.HIGHEST_PRECEDENCE + 3)
                .addPathPatterns(ADMIN_PATHS)
                .excludePathPatterns(PUBLIC_ADMIN_PATHS)
                .excludePathPatterns(PUBLIC_AUTHENTICATION_PATHS);
    }
}
