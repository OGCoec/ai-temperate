package com.example.temperate.web.auth.config;

import com.example.temperate.web.auth.interceptor.BrowserSessionSecurityInterceptor;
import com.example.temperate.web.auth.interceptor.RegistrationFlowInterceptor;
import com.example.temperate.web.auth.interceptor.UserSessionAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 认证相关 MVC 拦截器的路由装配配置。
 *
 * <p>用途：分别把注册流程凭据校验、H5 会话来源校验和普通 API 的 RT-first 会话认证绑定到对应路径。</p>
 *
 * <p>安全边界：普通业务认证固定使用 MVC Interceptor；管理员仍使用独立会话过滤器，注册流程端点也不会扩大到其他 API。</p>
 */
@Configuration
public class AuthWebMvcConfiguration implements WebMvcConfigurer {

    private static final String BAR_PAYMENT_CALLBACK_PATH =
            "/api/payment/bar/notify";
    private static final String LIUHAO_PAYMENT_CALLBACK_PATH =
            "/api/payment/liuhao/notify";
    private static final int REGISTRATION_FLOW_ORDER =
            Ordered.HIGHEST_PRECEDENCE + 20;
    private static final int BROWSER_SESSION_SECURITY_ORDER =
            Ordered.HIGHEST_PRECEDENCE + 21;
    private static final int USER_SESSION_AUTHENTICATION_ORDER =
            Ordered.HIGHEST_PRECEDENCE + 22;

    private final UserSessionAuthenticationInterceptor userSessionInterceptor;
    private final RegistrationFlowInterceptor registrationFlowInterceptor;
    private final BrowserSessionSecurityInterceptor browserSessionSecurityInterceptor;

    public AuthWebMvcConfiguration(
            UserSessionAuthenticationInterceptor userSessionInterceptor,
            RegistrationFlowInterceptor registrationFlowInterceptor,
            BrowserSessionSecurityInterceptor browserSessionSecurityInterceptor) {
        this.userSessionInterceptor = userSessionInterceptor;
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
        // H5 的 bootstrap/logout 额外校验 Origin 与 Fetch Metadata，Android 由拦截器显式放行。
        registry.addInterceptor(browserSessionSecurityInterceptor)
                .order(BROWSER_SESSION_SECURITY_ORDER)
                .addPathPatterns(
                        "/api/auth/session/bootstrap",
                        "/api/auth/session/logout",
                        "/api/auth/session/logout-all");
        // 普通业务 API 必须同时验证 RT 与 AT；认证流程本身继续使用各自的短期凭据。
        registry.addInterceptor(userSessionInterceptor)
                .order(USER_SESSION_AUTHENTICATION_ORDER)
                .addPathPatterns("/api/**")
                // 管理员命名空间由独立会话过滤器处理；PreAuth 与 Challenge 必须先于用户会话认证建立安全上下文。
                .excludePathPatterns(
                        "/api/auth/**",
                        "/api/admin/**",
                        "/api/_edge/pre-auth",
                        "/api/_edge/risk-challenge",
                        "/api/_edge/webrtc/start",
                        "/api/_edge/webrtc/report",
                        // BAR 服务器不具备用户 RT/AT；该精确入口改由版本化 HMAC 和权威主动查询完成身份确认。
                        BAR_PAYMENT_CALLBACK_PATH,
                        // 六号服务器同样没有用户会话；固定入口由 RSA 验签、时间窗和主动查询完成认证。
                        LIUHAO_PAYMENT_CALLBACK_PATH,
                        "/api/health");
        // 全设备退出虽然位于认证路由下，仍必须先通过 RT-first 会话认证确定撤销目标用户。
        registry.addInterceptor(userSessionInterceptor)
                .order(USER_SESSION_AUTHENTICATION_ORDER)
                .addPathPatterns("/api/auth/session/logout-all");
    }
}
