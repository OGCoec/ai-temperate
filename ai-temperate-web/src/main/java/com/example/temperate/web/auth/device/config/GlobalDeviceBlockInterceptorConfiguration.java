package com.example.temperate.web.auth.device.config;

import com.example.temperate.web.auth.device.interceptor.GlobalDeviceBlockInterceptor;
import java.util.Objects;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 将全局设备封禁能力注册在网络风险之后的 MVC 优先级。
 *
 * <p>拦截器优先级只作用于 MVC HandlerInterceptor 链；请求仍会先经过 Servlet Filter 与 Spring Security，再进入 DispatcherServlet 和该拦截器。</p>
 */
@Configuration
public class GlobalDeviceBlockInterceptorConfiguration implements WebMvcConfigurer {

    private final GlobalDeviceBlockInterceptor interceptor;

    public GlobalDeviceBlockInterceptorConfiguration(GlobalDeviceBlockInterceptor interceptor) {
        this.interceptor = Objects.requireNonNull(interceptor);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .order(Ordered.HIGHEST_PRECEDENCE + 10)
                .addPathPatterns(
                        "/api/auth/login",
                        "/api/auth/login/**",
                        "/api/auth/register",
                        "/api/auth/register/**",
                        "/api/auth/password-reset",
                        "/api/auth/password-reset/**",
                        "/api/auth/oauth2",
                        "/api/auth/oauth2/**",
                        "/api/auth/session/bootstrap",
                        "/api/users/me/voice/session-tickets")
                .excludePathPatterns(
                        "/api/auth/session/logout",
                        "/api/auth/session/logout-all");
    }
}
