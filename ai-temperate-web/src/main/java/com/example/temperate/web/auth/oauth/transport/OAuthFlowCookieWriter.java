package com.example.temperate.web.auth.oauth.transport;

import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 统一隔离 H5 OAuth Flow、浏览器握手和补手机号子流程的 HttpOnly Cookie。
 *
 * <p>握手 Cookie 使用 Lax 以便 Provider 顶层回调携带；Flow 与手机号材料使用 Strict，JavaScript 不读取
 * 原始凭据。Cookie 的到期时间只缩小浏览器暴露窗口，Redis 仍是流程状态与绝对期限的事实来源。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.oauth", name = "enabled", havingValue = "true")
public final class OAuthFlowCookieWriter {

    public static final String FLOW_COOKIE = "oauth_flow";
    public static final String HANDSHAKE_COOKIE = "oauth_handshake";
    public static final String PHONE_FLOW_COOKIE = "oauth_phone_flow";
    public static final String PHONE_CHALLENGE_COOKIE = "oauth_phone_challenge";
    private static final String PATH = "/api/auth/oauth2";
    private static final Duration HANDSHAKE_TTL = Duration.ofMinutes(10);
    private static final Duration FLOW_TTL = Duration.ofMinutes(30);

    private final String cookieDomain;

    public OAuthFlowCookieWriter(AuthSecurityProperties properties) {
        this.cookieDomain = properties.cookies().domain();
    }

    public void writeH5Start(
            HttpServletResponse response,
            String rawFlowToken,
            String rawBrowserBinding) {
        add(response, FLOW_COOKIE, rawFlowToken, FLOW_TTL, "Strict");
        add(response, HANDSHAKE_COOKIE, rawBrowserBinding, HANDSHAKE_TTL, "Lax");
    }

    public void writeBrowserHandshake(
            HttpServletResponse response, String rawBrowserBinding) {
        add(response, HANDSHAKE_COOKIE, rawBrowserBinding, HANDSHAKE_TTL, "Lax");
    }

    public void writePhoneFlow(
            HttpServletResponse response,
            String rawPhoneFlowToken,
            String challengeHandle) {
        add(response, PHONE_FLOW_COOKIE, rawPhoneFlowToken, FLOW_TTL, "Strict");
        add(response, PHONE_CHALLENGE_COOKIE, challengeHandle, FLOW_TTL, "Strict");
    }

    public OAuthH5Cookies read(HttpServletRequest request) {
        return new OAuthH5Cookies(
                value(request, FLOW_COOKIE),
                value(request, HANDSHAKE_COOKIE),
                value(request, PHONE_FLOW_COOKIE),
                value(request, PHONE_CHALLENGE_COOKIE));
    }

    public void clearHandshake(HttpServletResponse response) {
        add(response, HANDSHAKE_COOKIE, "", Duration.ZERO, "Lax");
    }

    public void clearPhoneFlow(HttpServletResponse response) {
        add(response, PHONE_FLOW_COOKIE, "", Duration.ZERO, "Strict");
        add(response, PHONE_CHALLENGE_COOKIE, "", Duration.ZERO, "Strict");
    }

    public void clearAll(HttpServletResponse response) {
        add(response, FLOW_COOKIE, "", Duration.ZERO, "Strict");
        clearHandshake(response);
        clearPhoneFlow(response);
    }

    private void add(
            HttpServletResponse response,
            String name,
            String value,
            Duration maxAge,
            String sameSite) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .secure(true)
                .httpOnly(true)
                .sameSite(sameSite)
                .path(PATH)
                .maxAge(maxAge);
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    private static String value(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 汇总 H5 OAuth 请求可以从隔离 Cookie 读取的短时材料。
     */
    public record OAuthH5Cookies(
            String rawFlowToken,
            String rawBrowserBinding,
            String rawPhoneFlowToken,
            String phoneChallengeHandle) {
    }
}
