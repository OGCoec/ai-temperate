package com.example.temperate.web.auth.flow.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证 H5 注册和找回密码流程材料只能通过 HttpOnly Cookie 写入、读取与清理。
 *
 * <p>职责边界：本测试只检查 Web 传输层 Cookie 属性与路径，不替代服务层对流程令牌 HMAC、设备绑定和过期时间的校验。</p>
 */
class AuthFlowCookieWriterTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Test
    void writesRegistrationFlowCookiesWithStrictHttpOnlyPolicy() {
        AuthFlowCookieWriter writer = writerWithDomain("");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.writeRegistration(
                response,
                "register-token",
                "register-csrf",
                "challenge-handle",
                NOW.plus(Duration.ofMinutes(10)));

        List<String> cookies = List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE));
        assertThat(cookie(cookies, AuthFlowCookieWriter.REGISTER_TOKEN_COOKIE))
                .contains("register_flow_token=register-token")
                .contains("Path=/api/auth/register")
                .contains("Max-Age=600")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Domain=");
        assertThat(cookie(cookies, AuthFlowCookieWriter.REGISTER_CSRF_COOKIE))
                .contains("register_flow_csrf=register-csrf")
                .contains("Path=/api/auth/register")
                .contains("HttpOnly");
        assertThat(cookie(cookies, AuthFlowCookieWriter.REGISTER_CHALLENGE_COOKIE))
                .contains("register_challenge=challenge-handle")
                .contains("Path=/api/auth/register")
                .contains("HttpOnly");
    }

    @Test
    void writesPasswordResetCookiesOnSeparateFlowAndCompletionPaths() {
        AuthFlowCookieWriter writer = writerWithDomain("niko000o.site");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.writePasswordResetFlow(
                response, "reset-token", NOW.plus(Duration.ofMinutes(8)));
        writer.writeForgetToken(
                response, "forget-token", NOW.plus(Duration.ofMinutes(5)));

        List<String> cookies = List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE));
        assertThat(cookie(cookies, AuthFlowCookieWriter.RESET_FLOW_COOKIE))
                .contains("reset_flow_token=reset-token")
                .contains("Path=/api/auth/password-reset")
                .contains("Max-Age=480")
                .contains("Domain=niko000o.site")
                .contains("HttpOnly")
                .contains("SameSite=Strict");
        assertThat(cookie(cookies, AuthFlowCookieWriter.FORGET_TOKEN_COOKIE))
                .contains("forget_token=forget-token")
                .contains("Path=/api/auth/password-reset/complete")
                .contains("Max-Age=300")
                .contains("Domain=niko000o.site")
                .contains("HttpOnly")
                .contains("SameSite=Strict");
    }

    @Test
    void readsFlowCookiesFromBrowserRequest() {
        AuthFlowCookieWriter writer = writerWithDomain("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(AuthFlowCookieWriter.REGISTER_TOKEN_COOKIE, "register-token"),
                new Cookie(AuthFlowCookieWriter.REGISTER_CSRF_COOKIE, "register-csrf"),
                new Cookie(AuthFlowCookieWriter.REGISTER_CHALLENGE_COOKIE, "challenge-handle"),
                new Cookie(AuthFlowCookieWriter.RESET_FLOW_COOKIE, "reset-token"),
                new Cookie(AuthFlowCookieWriter.FORGET_TOKEN_COOKIE, "forget-token"));

        AuthFlowCookieWriter.RegistrationFlowCookies registration =
                writer.registration(request);

        assertThat(registration.registerToken()).isEqualTo("register-token");
        assertThat(registration.flowCsrf()).isEqualTo("register-csrf");
        assertThat(registration.challengeHandle()).isEqualTo("challenge-handle");
        assertThat(writer.resetFlowToken(request)).isEqualTo("reset-token");
        assertThat(writer.forgetToken(request)).isEqualTo("forget-token");
    }

    @Test
    void clearsEveryFlowCookieUsingOriginalPaths() {
        AuthFlowCookieWriter writer = writerWithDomain("");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.clearRegistration(response);
        writer.clearPasswordReset(response);

        List<String> cookies = List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE));
        assertThat(cookie(cookies, AuthFlowCookieWriter.REGISTER_TOKEN_COOKIE))
                .contains("Path=/api/auth/register")
                .contains("Max-Age=0");
        assertThat(cookie(cookies, AuthFlowCookieWriter.REGISTER_CSRF_COOKIE))
                .contains("Path=/api/auth/register")
                .contains("Max-Age=0");
        assertThat(cookie(cookies, AuthFlowCookieWriter.REGISTER_CHALLENGE_COOKIE))
                .contains("Path=/api/auth/register")
                .contains("Max-Age=0");
        assertThat(cookie(cookies, AuthFlowCookieWriter.RESET_FLOW_COOKIE))
                .contains("Path=/api/auth/password-reset")
                .contains("Max-Age=0");
        assertThat(cookie(cookies, AuthFlowCookieWriter.FORGET_TOKEN_COOKIE))
                .contains("Path=/api/auth/password-reset/complete")
                .contains("Max-Age=0");
    }

    private static AuthFlowCookieWriter writerWithDomain(String domain) {
        AuthSecurityProperties properties = mock(AuthSecurityProperties.class);
        when(properties.cookies()).thenReturn(new AuthSecurityProperties.Cookies(
                cookie(true, "/api"),
                cookie(true, "/api"),
                cookie(false, "/"),
                cookie(true, "/api/auth/register"),
                cookie(true, "/api/auth/register"),
                cookie(true, "/api/auth/password-reset"),
                cookie(true, "/api/auth/password-reset/complete"),
                cookie(true, "/api/auth/login/totp"),
                domain));
        return new AuthFlowCookieWriter(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AuthSecurityProperties.CookieSettings cookie(boolean httpOnly, String path) {
        return new AuthSecurityProperties.CookieSettings(
                true, httpOnly, AuthSecurityProperties.SameSite.STRICT, path);
    }

    private static String cookie(List<String> cookies, String name) {
        return cookies.stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElseThrow();
    }
}
