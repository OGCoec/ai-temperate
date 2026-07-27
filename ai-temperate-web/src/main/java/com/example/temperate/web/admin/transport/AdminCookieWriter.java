package com.example.temperate.web.admin.transport;

import com.example.temperate.service.admin.config.properties.AdminProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 统一写入、读取和清理管理员 H5 注册、登录、会话与双提交 CSRF Cookie。
 *
 * <p>Android 不调用写入方法；原始 Token 只允许短暂经过本组件进入 HttpOnly Cookie，禁止进入日志或前端存储。</p>
 */
@Component
public final class AdminCookieWriter {

    public static final String SESSION_COOKIE = "admin_session";
    public static final String CSRF_COOKIE = "ADMIN-XSRF-TOKEN";
    public static final String REGISTER_TOKEN_COOKIE = "admin_register_token";
    public static final String REGISTER_CSRF_COOKIE = "admin_register_csrf";
    public static final String REGISTER_CHALLENGE_COOKIE = "admin_register_challenge";
    public static final String LOGIN_TOKEN_COOKIE = "admin_login_flow";
    public static final String LOGIN_CSRF_COOKIE = "admin_login_csrf";
    public static final String LOGIN_CHALLENGE_COOKIE = "admin_login_challenge";

    private final AdminProperties properties;
    private final Clock clock;

    public AdminCookieWriter(AdminProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    public void writeRegistration(
            HttpServletResponse response,
            String token,
            String csrf,
            String challenge,
            Instant expiresAt) {
        Duration maxAge = remaining(expiresAt);
        addSensitive(response, REGISTER_TOKEN_COOKIE, token, maxAge,
                properties.cookies().registerFlow());
        addCsrf(response, REGISTER_CSRF_COOKIE, csrf, maxAge,
                properties.cookies().registerCsrf());
        addSensitive(response, REGISTER_CHALLENGE_COOKIE, challenge, maxAge,
                properties.cookies().registerFlow());
    }

    public void writeLogin(
            HttpServletResponse response,
            String token,
            String csrf,
            String challenge,
            Instant expiresAt) {
        Duration maxAge = remaining(expiresAt);
        addSensitive(response, LOGIN_TOKEN_COOKIE, token, maxAge,
                properties.cookies().loginFlow());
        addCsrf(response, LOGIN_CSRF_COOKIE, csrf, maxAge,
                properties.cookies().loginCsrf());
        addSensitive(response, LOGIN_CHALLENGE_COOKIE, challenge, maxAge,
                properties.cookies().loginFlow());
    }

    public void writeSession(
            HttpServletResponse response,
            String token,
            String csrf,
            Instant expiresAt) {
        Duration maxAge = remaining(expiresAt);
        addSensitive(response, SESSION_COOKIE, token, maxAge,
                properties.cookies().session());
        addCsrf(response, CSRF_COOKIE, csrf, maxAge, properties.cookies().csrf());
    }

    public void refreshSession(
            HttpServletResponse response,
            String token,
            String csrf,
            Instant expiresAt) {
        writeSession(response, token, csrf, expiresAt);
    }

    public RegistrationCookies registration(HttpServletRequest request) {
        return new RegistrationCookies(
                value(request, REGISTER_TOKEN_COOKIE),
                value(request, REGISTER_CSRF_COOKIE),
                value(request, REGISTER_CHALLENGE_COOKIE));
    }

    public LoginCookies login(HttpServletRequest request) {
        return new LoginCookies(
                value(request, LOGIN_TOKEN_COOKIE),
                value(request, LOGIN_CSRF_COOKIE),
                value(request, LOGIN_CHALLENGE_COOKIE));
    }

    public String sessionToken(HttpServletRequest request) {
        return value(request, SESSION_COOKIE);
    }

    public String csrfToken(HttpServletRequest request) {
        return value(request, CSRF_COOKIE);
    }

    public void clearRegistration(HttpServletResponse response) {
        clearSensitive(response, REGISTER_TOKEN_COOKIE, properties.cookies().registerFlow());
        clearCsrf(response, REGISTER_CSRF_COOKIE, properties.cookies().registerCsrf());
        clearSensitive(response, REGISTER_CHALLENGE_COOKIE,
                properties.cookies().registerFlow());
    }

    public void clearLogin(HttpServletResponse response) {
        clearSensitive(response, LOGIN_TOKEN_COOKIE, properties.cookies().loginFlow());
        clearCsrf(response, LOGIN_CSRF_COOKIE, properties.cookies().loginCsrf());
        clearSensitive(response, LOGIN_CHALLENGE_COOKIE, properties.cookies().loginFlow());
    }

    public void clearSession(HttpServletResponse response) {
        clearSensitive(response, SESSION_COOKIE, properties.cookies().session());
        clearCsrf(response, CSRF_COOKIE, properties.cookies().csrf());
    }

    private Duration remaining(Instant expiresAt) {
        Duration value = Duration.between(clock.instant(), expiresAt);
        return value.isNegative() || value.isZero() ? Duration.ofSeconds(1) : value;
    }

    private void addSensitive(
            HttpServletResponse response,
            String name,
            String value,
            Duration maxAge,
            AdminProperties.Cookie settings) {
        add(response, name, value, maxAge, settings, properties.cookies().domain());
    }

    private void addCsrf(
            HttpServletResponse response,
            String name,
            String value,
            Duration maxAge,
            AdminProperties.Cookie settings) {
        String sensitiveDomain = properties.cookies().domain();
        String csrfDomain = properties.cookies().csrfDomain();
        if (!Objects.equals(sensitiveDomain, csrfDomain)) {
            // 切换到父域前先清理旧作用域的同名 Cookie，避免 Servlet 收到两个值后选中旧 CSRF。
            add(response, name, "", Duration.ZERO, settings, sensitiveDomain);
        }
        add(response, name, value, maxAge, settings, csrfDomain);
    }

    private void clearSensitive(
            HttpServletResponse response,
            String name,
            AdminProperties.Cookie settings) {
        add(response, name, "", Duration.ZERO, settings, properties.cookies().domain());
    }

    private void clearCsrf(
            HttpServletResponse response,
            String name,
            AdminProperties.Cookie settings) {
        String sensitiveDomain = properties.cookies().domain();
        String csrfDomain = properties.cookies().csrfDomain();
        add(response, name, "", Duration.ZERO, settings, csrfDomain);
        if (!Objects.equals(sensitiveDomain, csrfDomain)) {
            // 回滚或重新开始流程时两个作用域都必须清理，保证旧 Cookie 不会重新参与双提交比较。
            add(response, name, "", Duration.ZERO, settings, sensitiveDomain);
        }
    }

    private void add(
            HttpServletResponse response,
            String name,
            String value,
            Duration maxAge,
            AdminProperties.Cookie settings,
            String domain) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .secure(settings.secure())
                .httpOnly(settings.httpOnly())
                .sameSite(settings.sameSite())
                .path(settings.path())
                .maxAge(maxAge);
        if (domain != null && !domain.isBlank()) {
            builder.domain(domain);
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
     * H5 管理员注册 Flow 的三份短期 Cookie。
     */
    public record RegistrationCookies(
            String token,
            String csrf,
            String challenge) {

        @Override
        public String toString() {
            return "RegistrationCookies[redacted]";
        }
    }

    /**
     * H5 管理员登录 Flow 的三份短期 Cookie。
     */
    public record LoginCookies(
            String token,
            String csrf,
            String challenge) {

        @Override
        public String toString() {
            return "LoginCookies[redacted]";
        }
    }
}
