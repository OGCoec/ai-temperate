package com.example.temperate.web.auth.interceptor;

import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * H5 刷新、恢复和退出会话的浏览器来源校验拦截器。
 *
 * <p>用途：对自动携带 Cookie 的浏览器请求同时检查显式允许 Origin 与同源/同站 Fetch Metadata。</p>
 *
 * <p>安全原理：即使 SameSite Cookie 已限制跨站发送，仍使用 Origin 和 Fetch Metadata 形成纵深防御；Android
 * 不使用业务 Cookie，因此不套用浏览器来源规则。</p>
 */
@Component
public final class BrowserSessionSecurityInterceptor implements HandlerInterceptor {

    private static final String PLATFORM_HEADER = "X-Client-Platform";
    private final Set<String> allowedOrigins;

    public BrowserSessionSecurityInterceptor(AuthSecurityProperties properties) {
        this.allowedOrigins = Set.copyOf(properties.cors().allowedOrigins());
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        // Android 会话不依赖浏览器 Cookie，交由其 Authorization/请求体与服务层会话校验处理。
        if ("ANDROID".equalsIgnoreCase(request.getHeader(PLATFORM_HEADER))) {
            return true;
        }
        String origin = request.getHeader("Origin");
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        boolean trustedFetchSite = "same-origin".equalsIgnoreCase(fetchSite)
                || "same-site".equalsIgnoreCase(fetchSite);
        if (!allowedOrigins.contains(origin) || !trustedFetchSite) {
            throw new SessionAuthenticationException(
                    SessionAuthenticationErrorCode.CSRF_INVALID,
                    "Browser request origin is not allowed.", false);
        }
        return true;
    }
}
