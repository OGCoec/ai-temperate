package com.example.temperate.web.risk.webrtc;

import java.util.Objects;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 将 WebRTC 校验注册在网络风险之后、其他 MVC 拦截器之前，并排除自身闭环端点。
 */
@Configuration
public class WebRtcWebMvcConfiguration implements WebMvcConfigurer {

    private static final String BAR_PAYMENT_CALLBACK_PATH =
            "/api/payment/bar/notify";

    private final WebRtcVerificationInterceptor interceptor;

    public WebRtcWebMvcConfiguration(WebRtcVerificationInterceptor interceptor) {
        this.interceptor = Objects.requireNonNull(interceptor);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .order(Ordered.HIGHEST_PRECEDENCE + 1)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/health",
                        "/api/_edge/cookie-scope",
                        "/api/admin/_edge/cookie-scope",
                        "/api/_edge/pre-auth",
                        "/api/admin/_edge/pre-auth",
                        "/api/_edge/risk-challenge",
                        "/api/admin/_edge/risk-challenge",
                        // BAR 的服务器请求没有浏览器 WebRTC 状态；只排除签名回调精确路径，不扩大普通用户接口。
                        BAR_PAYMENT_CALLBACK_PATH,
                        /*
                         * Provider 顶层导航和回调无法携带站内 WebRTC 请求上下文；这里只精确排除两条入口，
                         * 其完整性继续由一次性 state、PKCE、OIDC nonce 与握手 Cookie 共同保证。
                         */
                        "/api/auth/oauth2/authorization/**",
                        "/api/auth/oauth2/code/**",
                        "/api/_edge/webrtc/start",
                        "/api/_edge/webrtc/report",
                        "/api/admin/_edge/webrtc/start",
                        "/api/admin/_edge/webrtc/report",
                        /*
                         * 样式与脚本本身不携带用户状态，且必须在 WebRTC 校验页面建立状态之前加载；
                         * 只排除四条精确资源路径，不能扩展为验证码目录通配。
                         */
                        "/api/auth/turnstile/page.css",
                        "/api/auth/turnstile/page.js",
                        "/api/admin/auth/hcaptcha/page.css",
                        "/api/admin/auth/hcaptcha/page.js");
    }
}
