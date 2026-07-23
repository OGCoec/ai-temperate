package com.example.temperate.web.auth.interceptor;

import com.example.temperate.service.auth.session.access.AccessSessionService;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 普通业务 API 的 Access Token 身份认证拦截器。
 *
 * <p>用途：H5 仅从 {@code access_token} Cookie 读取 AT，Android 仅从 {@code Authorization: Bearer} 读取 AT，
 * 再将经服务端验证的主体写入请求属性与 Spring Security 上下文。</p>
 *
 * <p>安全边界：两种来源严格隔离，不接受跨平台回退；平台头只决定读取位置，Token 本身仍必须通过会话服务校验。</p>
 */
@Component
public final class AccessTokenAuthenticationInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL_ATTRIBUTE =
            AccessTokenAuthenticationInterceptor.class.getName() + ".principal";
    private final AccessSessionService accessSessionService;

    public AccessTokenAuthenticationInterceptor(
            AccessSessionService accessSessionService) {
        this.accessSessionService = accessSessionService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(
                request.getHeader("X-Client-Platform"));
        // 不尝试在另一种来源兜底读取，防止 Cookie 与 Authorization 协议意外混用。
        String token = platform == AuthClientPlatform.ANDROID
                ? bearerToken(request)
                : cookieToken(request);
        SessionPrincipal principal = accessSessionService.authenticate(token);
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, token, List.of()));
        return true;
    }

    private static String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
    }

    private static String cookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthCookieWriter.ACCESS_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
