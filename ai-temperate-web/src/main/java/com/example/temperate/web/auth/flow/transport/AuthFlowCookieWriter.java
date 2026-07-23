package com.example.temperate.web.auth.flow.transport;

import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 统一写入、读取和清理 H5 注册与找回密码流程 Cookie。
 *
 * <p>用途：把短期流程凭据限制在浏览器 HttpOnly Cookie 传输边界内，避免 H5 JavaScript 和持久化存储接触
 * register/reset/forget 等原文材料；Android 不使用这些 Cookie，继续通过显式 Header 与 Keystore 协作。</p>
 */
@Component
public final class AuthFlowCookieWriter {

    public static final String REGISTER_TOKEN_COOKIE = "register_flow_token";
    public static final String REGISTER_CSRF_COOKIE = "register_flow_csrf";
    public static final String REGISTER_CHALLENGE_COOKIE = "register_challenge";
    public static final String RESET_FLOW_COOKIE = "reset_flow_token";
    public static final String FORGET_TOKEN_COOKIE = "forget_token";

    private final AuthSecurityProperties properties;
    private final Clock clock;

    public AuthFlowCookieWriter(AuthSecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void writeRegistration(
            HttpServletResponse response,
            String registerToken,
            String flowCsrf,
            String challengeHandle,
            Instant expiresAt) {
        // 注册流程三份材料必须按同一到期时间写入，避免 H5 只残留其中一部分造成不可恢复的半流程状态。
        Duration maxAge = remaining(expiresAt);
        add(response, REGISTER_TOKEN_COOKIE, registerToken, maxAge,
                properties.cookies().registerFlow());
        add(response, REGISTER_CSRF_COOKIE, flowCsrf, maxAge,
                properties.cookies().registerFlow());
        add(response, REGISTER_CHALLENGE_COOKIE, challengeHandle, maxAge,
                properties.cookies().registerChallenge());
    }

    public void clearRegistration(HttpServletResponse response) {
        clear(response, REGISTER_TOKEN_COOKIE, properties.cookies().registerFlow());
        clear(response, REGISTER_CSRF_COOKIE, properties.cookies().registerFlow());
        clear(response, REGISTER_CHALLENGE_COOKIE, properties.cookies().registerChallenge());
    }

    public void writePasswordResetFlow(
            HttpServletResponse response,
            String resetFlowToken,
            Instant expiresAt) {
        add(response, RESET_FLOW_COOKIE, resetFlowToken, remaining(expiresAt),
                properties.cookies().passwordResetFlow());
    }

    public void writeForgetToken(
            HttpServletResponse response,
            String forgetToken,
            Instant expiresAt) {
        add(response, FORGET_TOKEN_COOKIE, forgetToken, remaining(expiresAt),
                properties.cookies().passwordResetForget());
    }

    public void clearPasswordResetFlow(HttpServletResponse response) {
        clear(response, RESET_FLOW_COOKIE, properties.cookies().passwordResetFlow());
    }

    public void clearForgetToken(HttpServletResponse response) {
        clear(response, FORGET_TOKEN_COOKIE, properties.cookies().passwordResetForget());
    }

    public void clearPasswordReset(HttpServletResponse response) {
        clearPasswordResetFlow(response);
        clearForgetToken(response);
    }

    public RegistrationFlowCookies registration(HttpServletRequest request) {
        return new RegistrationFlowCookies(
                cookieValue(request, REGISTER_TOKEN_COOKIE),
                cookieValue(request, REGISTER_CSRF_COOKIE),
                cookieValue(request, REGISTER_CHALLENGE_COOKIE));
    }

    public String resetFlowToken(HttpServletRequest request) {
        return cookieValue(request, RESET_FLOW_COOKIE);
    }

    public String forgetToken(HttpServletRequest request) {
        return cookieValue(request, FORGET_TOKEN_COOKIE);
    }

    private Duration remaining(Instant expiresAt) {
        Duration maxAge = Duration.between(clock.instant(), expiresAt);
        return maxAge.isNegative() || maxAge.isZero() ? Duration.ofSeconds(1) : maxAge;
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
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .secure(settings.secure())
                .httpOnly(settings.httpOnly())
                .sameSite(sameSite(settings.sameSite()))
                .path(settings.path());
        if (maxAge != null) {
            builder.maxAge(maxAge);
        }
        String domain = properties.cookies().domain();
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    private static String cookieValue(HttpServletRequest request, String name) {
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

    private static String sameSite(AuthSecurityProperties.SameSite sameSite) {
        String name = sameSite.name().toLowerCase();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * H5 注册流程 Cookie 中保存的三份流程材料。
     *
     * <p>用途：让 Controller 和拦截器用同一种对象构造领域层访问材料，避免一处从 Cookie 读、一处从 Header 读导致
     * 注册流程安全边界不一致。</p>
     */
    public record RegistrationFlowCookies(
            String registerToken,
            String flowCsrf,
            String challengeHandle) {
    }
}
