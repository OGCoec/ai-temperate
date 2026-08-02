package com.example.temperate.web.auth.config;

import com.example.temperate.web.auth.interceptor.AccessTokenAuthenticationInterceptor;
import com.example.temperate.web.auth.interceptor.BrowserSessionSecurityInterceptor;
import com.example.temperate.web.auth.interceptor.RegistrationFlowInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 认证相关 MVC 拦截器的路由装配配置。
 *
 * <p>用途：分别把注册流程凭据校验、H5 会话来源校验和普通 API 的 AT 身份认证绑定到对应路径。</p>
 *
 * <p>安全边界：会话端点不复用普通 AT 拦截器，注册流程端点不被扩大到其他 API，避免不同凭据协议互相回退。</p>
 */
@Configuration
public class AuthWebMvcConfiguration implements WebMvcConfigurer {

    private static final int REGISTRATION_FLOW_ORDER =
            Ordered.HIGHEST_PRECEDENCE + 20;
    private static final int BROWSER_SESSION_SECURITY_ORDER =
            Ordered.HIGHEST_PRECEDENCE + 21;
    private static final int ACCESS_TOKEN_AUTHENTICATION_ORDER =
            Ordered.HIGHEST_PRECEDENCE + 22;

    private final AccessTokenAuthenticationInterceptor accessTokenInterceptor;
    private final RegistrationFlowInterceptor registrationFlowInterceptor;
    private final BrowserSessionSecurityInterceptor browserSessionSecurityInterceptor;

    public AuthWebMvcConfiguration(
            AccessTokenAuthenticationInterceptor accessTokenInterceptor,
            RegistrationFlowInterceptor registrationFlowInterceptor,
            BrowserSessionSecurityInterceptor browserSessionSecurityInterceptor) {
        this.accessTokenInterceptor = accessTokenInterceptor;
        this.registrationFlowInterceptor = registrationFlowInterceptor;
        this.browserSessionSecurityInterceptor = browserSessionSecurityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册令牌、流程 CSRF 和设备绑定只在注册状态机接口中验证。
        registry.addInterceptor(registrationFlowInterceptor)
                .order(REGISTRATION_FLOW_ORDER)
                .addPathPatterns(
                        "/api/auth/register/status",
                        "/api/auth/register/turnstile",
                        "/api/auth/register/codes/**",
                        "/api/auth/register/complete");
        // H5 的 refresh/bootstrap/logout 额外校验 Origin 与 Fetch Metadata，Android 由拦截器显式放行。
        registry.addInterceptor(browserSessionSecurityInterceptor)
                .order(BROWSER_SESSION_SECURITY_ORDER)
                .addPathPatterns(
                        "/api/auth/session/refresh",
                        "/api/auth/session/bootstrap",
                        "/api/auth/session/logout",
                        "/api/auth/session/logout-all");
        // 普通业务 API 才需要 AT；认证流程本身使用各自的短期或刷新凭据。
        registry.addInterceptor(accessTokenInterceptor)
                .order(ACCESS_TOKEN_AUTHENTICATION_ORDER)
                .addPathPatterns("/api/**")
                // 管理员命名空间由独立会话过滤器处理；PreAuth 与 Challenge 必须先于 AT 建立安全上下文。
                .excludePathPatterns(
                        "/api/auth/**",
                        "/api/admin/**",
                        "/api/_edge/pre-auth",
                        "/api/_edge/risk-challenge",
                        "/api/_edge/webrtc/start",
                        "/api/_edge/webrtc/report",
                        "/api/health");
        // 全设备退出虽然位于认证路由下，仍必须使用 Access Token 确定撤销目标用户。
        registry.addInterceptor(accessTokenInterceptor)
                .order(ACCESS_TOKEN_AUTHENTICATION_ORDER)
                .addPathPatterns("/api/auth/session/logout-all");
    }
}
