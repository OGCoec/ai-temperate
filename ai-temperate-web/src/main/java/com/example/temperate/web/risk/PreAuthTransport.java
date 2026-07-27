package com.example.temperate.web.risk;

import com.example.temperate.service.risk.domain.RiskScope;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
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
            String rawToken,
            Instant expiresAt,
            Instant now) {
        Duration maxAge = Duration.between(now, expiresAt);
        ResponseCookie cookie = ResponseCookie.from(cookieName(scope), rawToken)
                .secure(true)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
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
