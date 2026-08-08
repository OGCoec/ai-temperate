package com.example.temperate.web.auth.flow.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证 H5 注册、找回密码和 TOTP 登录流程材料使用隔离的会话 Cookie 写入、读取与清理。
 *
 * <p>职责边界：本测试只检查 Web 传输层 Cookie 属性与路径，不替代服务层对流程令牌 HMAC、设备绑定和过期时间的校验。</p>
 */
class AuthFlowCookieWriterTest {

    @Test
    void writesRegistrationFlowAsStrictHttpOnlySessionCookies() {
        AuthFlowCookieWriter writer = writerWithDomain("");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.writeRegistration(
                response,
                "register-token",
                "register-csrf",
                "challenge-handle");

        List<String> cookies = List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE));
        assertThat(cookie(cookies, AuthFlowCookieWriter.REGISTER_TOKEN_COOKIE))
                .contains("register_flow_token=register-token")
                .contains("Path=/api/auth/register")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Max-Age")
                .doesNotContain("Expires")
                .doesNotContain("Domain=");
        assertThat(cookie(cookies, AuthFlowCookieWriter.REGISTER_CSRF_COOKIE))
                .contains("register_flow_csrf=register-csrf")
                .contains("Path=/api/auth/register")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Max-Age")
                .doesNotContain("Expires")
                .doesNotContain("Domain=");
        assertThat(cookie(cookies, AuthFlowCookieWriter.REGISTER_CHALLENGE_COOKIE))
                .contains("register_challenge=challenge-handle")
                .contains("Path=/api/auth/register")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Max-Age")
                .doesNotContain("Expires")
                .doesNotContain("Domain=");
    }

    @Test
    void writesPasswordResetSessionCookiesOnSeparateFlowAndCompletionPaths() {
        AuthFlowCookieWriter writer = writerWithDomain("niko000o.site");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.writePasswordResetFlow(response, "reset-token");
        writer.writeForgetToken(response, "forget-token");

        List<String> cookies = List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE));
        assertThat(cookie(cookies, AuthFlowCookieWriter.RESET_FLOW_COOKIE))
                .contains("reset_flow_token=reset-token")
                .contains("Path=/api/auth/password-reset")
                .contains("Domain=niko000o.site")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Max-Age")
                .doesNotContain("Expires");
        assertThat(cookie(cookies, AuthFlowCookieWriter.FORGET_TOKEN_COOKIE))
                .contains("forget_token=forget-token")
                .contains("Path=/api/auth/password-reset/complete")
                .contains("Domain=niko000o.site")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Max-Age")
                .doesNotContain("Expires");
    }

    @Test
    void writesTotpLoginFlowAsStrictHttpOnlySessionCookie() {
        AuthFlowCookieWriter writer = writerWithDomain("");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.writeTotpLoginFlow(response, "totp-flow-token");

        assertThat(cookie(
                        List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE)),
                        AuthFlowCookieWriter.TOTP_LOGIN_FLOW_COOKIE))
                .contains("totp_login_flow=totp-flow-token")
                .contains("Path=/api/auth/login/totp")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Max-Age")
                .doesNotContain("Expires")
                .doesNotContain("Domain=");
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
                new Cookie(AuthFlowCookieWriter.FORGET_TOKEN_COOKIE, "forget-token"),
                new Cookie(AuthFlowCookieWriter.TOTP_LOGIN_FLOW_COOKIE, "totp-flow-token"));

        AuthFlowCookieWriter.RegistrationFlowCookies registration =
                writer.registration(request);

        assertThat(registration.registerToken()).isEqualTo("register-token");
        assertThat(registration.flowCsrf()).isEqualTo("register-csrf");
        assertThat(registration.challengeHandle()).isEqualTo("challenge-handle");
        assertThat(writer.resetFlowToken(request)).isEqualTo("reset-token");
        assertThat(writer.forgetToken(request)).isEqualTo("forget-token");
        assertThat(writer.totpLoginFlowToken(request)).isEqualTo("totp-flow-token");
    }

    @Test
    void clearsEveryFlowCookieUsingOriginalPaths() {
        AuthFlowCookieWriter writer = writerWithDomain("");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.clearRegistration(response);
        writer.clearPasswordReset(response);
        writer.clearTotpLoginFlow(response);

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
        assertThat(cookie(cookies, AuthFlowCookieWriter.TOTP_LOGIN_FLOW_COOKIE))
                .contains("Path=/api/auth/login/totp")
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
        return new AuthFlowCookieWriter(properties);
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
