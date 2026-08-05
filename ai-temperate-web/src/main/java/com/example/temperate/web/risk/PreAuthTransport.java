package com.example.temperate.web.risk;

import com.example.temperate.service.risk.domain.RiskScope;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 隔离普通与管理员 PreAuth 的 Host-only Cookie 和 Android Header 传输。
 */
@Component
public final class PreAuthTransport {

    public static final String APP_HEADER = "X-AIT-PreAuth";
    public static final String RESET_HEADER = "X-AIT-PreAuth-Reset";
    public static final String USER_COOKIE = "__Host-ait-preauth";
    public static final String ADMIN_COOKIE = "__Host-ait-admin-preauth";

    public String read(
            HttpServletRequest request,
            RiskScope scope) {
        String header = request.getHeader(APP_HEADER);
        if (!hasText(request.getHeader("Origin")) && hasText(header)) {
            return header.trim();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String expected = cookieName(scope);
        return Arrays.stream(cookies)
                .filter(cookie -> expected.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    public void writeCookie(
            HttpServletResponse response,
            RiskScope scope,
            String rawToken) {
        // PreAuth 到期值仍需送达风险状态机以完成重新初始化，Cookie 生命周期不得复制 Redis TTL。
        ResponseCookie cookie = ResponseCookie.from(cookieName(scope), rawToken)
                .secure(true)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearCookie(HttpServletResponse response, RiskScope scope) {
        // 显式退出或不可恢复的会话错误必须复用 __Host- Cookie 的原始作用域才能可靠清理。
        ResponseCookie cookie = ResponseCookie.from(cookieName(scope), "")
                .secure(true)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String cookieName(RiskScope scope) {
        return scope == RiskScope.ADMIN ? ADMIN_COOKIE : USER_COOKIE;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
