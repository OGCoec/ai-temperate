package com.example.temperate.web.auth.session.transport;

import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 统一写入和清理 H5 认证 Cookie。
 *
 * <p>该组件负责按安全配置写入 access_token、refresh_token 和可读的 XSRF-TOKEN，并按相同
 * Path 和 Domain 清理当前及旧版 Cookie。它不负责生成 Token，也不负责判断会话是否有效。</p>
 */
@Component
public final class AuthCookieWriter {

    public static final String ACCESS_COOKIE = "access_token";
    public static final String REFRESH_COOKIE = "refresh_token";
    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String LEGACY_REFRESH_COOKIE = "rt";
    private static final String LEGACY_REFRESH_PATH = "/api/auth/session";

    private final AuthSecurityProperties properties;

    public AuthCookieWriter(AuthSecurityProperties properties) {
        this.properties = properties;
    }

    public void writeSession(
            HttpServletResponse response,
            String accessToken,
            String refreshToken,
            String csrfToken) {
        // 三个 Cookie 作为同一 H5 会话快照一起写入，避免部分更新遗留旧 CSRF 或旧 AT。
        writeAccessToken(response, accessToken);
        writeRefreshToken(response, refreshToken);
        writeCsrfToken(response, csrfToken);
        clearLegacyRefreshToken(response);
    }

    public void writeAccessToken(HttpServletResponse response, String accessToken) {
        // JWT 到期仍需随请求送达服务端完成 RT-first 同请求续签，因此浏览器会话内不得自动删除。
        add(response, ACCESS_COOKIE, accessToken, null,
                properties.cookies().access());
    }

    public void writeRefreshToken(
            HttpServletResponse response, String refreshToken) {
        // RT 是否有效只由 Redis Session 决定；Cookie 不复制服务端 TTL，避免两套倒计时发生漂移。
        add(response, REFRESH_COOKIE, refreshToken, null,
                properties.cookies().refresh());
        // 路径改变后浏览器可能同时保留两个同名 RT；写入新值时主动过期旧路径，避免服务端收到歧义 Cookie。
        clearLegacyRefreshPath(response);
    }

    public void writeCsrfToken(HttpServletResponse response, String csrfToken) {
        add(response, CSRF_COOKIE, csrfToken, null, properties.cookies().csrf());
    }

    public void clearSession(HttpServletResponse response) {
        // 清理必须复用写入时的 Path 和 Domain，否则浏览器会保留旧 Cookie。
        clear(response, ACCESS_COOKIE, properties.cookies().access());
        clear(response, REFRESH_COOKIE, properties.cookies().refresh());
        clearLegacyRefreshPath(response);
        clear(response, CSRF_COOKIE, properties.cookies().csrf());
        clearLegacyRefreshToken(response);
    }

    private void clearLegacyRefreshToken(HttpServletResponse response) {
        AuthSecurityProperties.CookieSettings refresh = properties.cookies().refresh();
        AuthSecurityProperties.CookieSettings legacy =
                new AuthSecurityProperties.CookieSettings(
                        refresh.secure(), refresh.httpOnly(), refresh.sameSite(), "/");
        // 历史 rt 使用根路径，必须单独按原路径清除，不能复用当前 refresh_token 的专用路径。
        clear(response, LEGACY_REFRESH_COOKIE, legacy);
    }

    private void clearLegacyRefreshPath(HttpServletResponse response) {
        AuthSecurityProperties.CookieSettings refresh = properties.cookies().refresh();
        AuthSecurityProperties.CookieSettings legacyPath =
                new AuthSecurityProperties.CookieSettings(
                        refresh.secure(),
                        refresh.httpOnly(),
                        refresh.sameSite(),
                        LEGACY_REFRESH_PATH);
        clear(response, REFRESH_COOKIE, legacyPath);
    }

    private void clear(
            HttpServletResponse response,
            String name,
            AuthSecurityProperties.CookieSettings settings) {
        add(response, name, "", Duration.ZERO, settings);
    }

    private void add(
            HttpServletResponse response,
            String name,
            String value,
            Duration maxAge,
            AuthSecurityProperties.CookieSettings settings) {
        add(response, name, value, maxAge, settings, properties.cookies().domain());
    }

    private static void add(
            HttpServletResponse response,
            String name,
            String value,
            Duration maxAge,
            AuthSecurityProperties.CookieSettings settings,
            String domain) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .secure(settings.secure())
                .httpOnly(settings.httpOnly())
                .sameSite(sameSite(settings.sameSite()))
                .path(settings.path());
        if (maxAge != null) {
            builder.maxAge(maxAge);
        }
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    private static String sameSite(AuthSecurityProperties.SameSite sameSite) {
        String name = sameSite.name().toLowerCase();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
