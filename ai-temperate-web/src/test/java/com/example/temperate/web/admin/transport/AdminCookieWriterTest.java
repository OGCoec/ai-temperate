package com.example.temperate.web.admin.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.config.properties.AdminProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证管理员生产 Cookie 全部保持 Host-only，并兼容迁移窗口清理旧父域作用域。
 */
class AdminCookieWriterTest {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(600);

    @Test
    void writesRegistrationCsrfForTheParentDomainAndKeepsFlowCookiesHostOnly() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer().writeRegistration(response, "register-token", "register-csrf",
                "register-challenge", EXPIRES_AT);

        assertSensitiveCookie(response, AdminCookieWriter.REGISTER_TOKEN_COOKIE,
                "register-token", "/api/admin/auth/register");
        assertSensitiveCookie(response, AdminCookieWriter.REGISTER_CHALLENGE_COOKIE,
                "register-challenge", "/api/admin/auth/register");
        assertSharedCsrfCookie(response, AdminCookieWriter.REGISTER_CSRF_COOKIE,
                "register-csrf");
        assertLegacyHostOnlyCsrfWasExpired(response,
                AdminCookieWriter.REGISTER_CSRF_COOKIE);
    }

    @Test
    void writesLoginCsrfForTheParentDomainAndKeepsFlowCookiesHostOnly() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer().writeLogin(response, "login-token", "login-csrf",
                "login-challenge", EXPIRES_AT);

        assertSensitiveCookie(response, AdminCookieWriter.LOGIN_TOKEN_COOKIE,
                "login-token", "/api/admin/auth/login");
        assertSensitiveCookie(response, AdminCookieWriter.LOGIN_CHALLENGE_COOKIE,
                "login-challenge", "/api/admin/auth/login");
        assertSharedCsrfCookie(response, AdminCookieWriter.LOGIN_CSRF_COOKIE,
                "login-csrf");
        assertLegacyHostOnlyCsrfWasExpired(response,
                AdminCookieWriter.LOGIN_CSRF_COOKIE);
    }

    @Test
    void writesSessionCsrfForTheParentDomainAndKeepsSessionHostOnly() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer().writeSession(response, "session-token", "session-csrf", EXPIRES_AT);

        assertSensitiveCookie(response, AdminCookieWriter.SESSION_COOKIE,
                "session-token", "/api/admin");
        assertSharedCsrfCookie(response, AdminCookieWriter.CSRF_COOKIE, "session-csrf");
        assertLegacyHostOnlyCsrfWasExpired(response, AdminCookieWriter.CSRF_COOKIE);
    }

    @Test
    void clearsEveryCsrfCookieFromBothTheLegacyAndSharedDomains() {
        assertCsrfClearedFromBothDomains(
                AdminCookieWriter.REGISTER_CSRF_COOKIE,
                response -> writer().clearRegistration(response));
        assertCsrfClearedFromBothDomains(
                AdminCookieWriter.LOGIN_CSRF_COOKIE,
                response -> writer().clearLogin(response));
        assertCsrfClearedFromBothDomains(
                AdminCookieWriter.CSRF_COOKIE,
                response -> writer().clearSession(response));
    }

    @Test
    void writesEveryAdministratorBusinessCookieHostOnlyBehindTheWorker() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AdminCookieWriter writer = writerWithDomains("", "");

        writer.writeRegistration(response, "register-token", "register-csrf",
                "register-challenge", EXPIRES_AT);
        writer.writeLogin(response, "login-token", "login-csrf",
                "login-challenge", EXPIRES_AT);
        writer.writeSession(response, "session-token", "session-csrf", EXPIRES_AT);

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .hasSize(8)
                .allSatisfy(cookie -> assertThat(cookie)
                        .contains("Secure")
                        .doesNotContain("Domain="));
    }

    private static AdminCookieWriter writer() {
        return writerWithDomains("", "niko000o.site");
    }

    private static AdminCookieWriter writerWithDomains(
            String sensitiveDomain,
            String csrfDomain) {
        return new AdminCookieWriter(
                properties(sensitiveDomain, csrfDomain),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AdminProperties properties(
            String sensitiveDomain,
            String csrfDomain) {
        AdminProperties defaults = AdminProperties.testDefaults(
                Path.of("target/admin-cookie-writer-test/complete.yaml"));
        AdminProperties.Cookies cookies = defaults.cookies();
        return new AdminProperties(
                defaults.configPath(),
                defaults.sessionHmacSecretBase64(),
                defaults.sessionTtl(),
                defaults.maxSessions(),
                defaults.registrationFlowTtl(),
                defaults.loginFlowTtl(),
                defaults.allowedOrigins(),
                defaults.hcaptcha(),
                new AdminProperties.Cookies(
                        sensitiveDomain,
                        csrfDomain,
                        cookies.session(),
                        cookies.csrf(),
                        cookies.registerFlow(),
                        cookies.registerCsrf(),
                        cookies.loginFlow(),
                        cookies.loginCsrf()));
    }

    private static void assertSensitiveCookie(
            MockHttpServletResponse response,
            String name,
            String value,
            String path) {
        String cookie = requiredCookie(response, name, value);
        assertThat(cookie)
                .contains("Path=" + path)
                .contains("Secure")
                .contains("HttpOnly")
                .containsIgnoringCase("SameSite=Strict")
                .doesNotContain("Domain=");
    }

    private static void assertSharedCsrfCookie(
            MockHttpServletResponse response,
            String name,
            String value) {
        String cookie = requiredCookie(response, name, value);
        assertThat(cookie)
                .contains("Path=/")
                .contains("Domain=niko000o.site")
                .contains("Secure")
                .containsIgnoringCase("SameSite=Strict")
                .doesNotContain("HttpOnly");
    }

    private static void assertLegacyHostOnlyCsrfWasExpired(
            MockHttpServletResponse response,
            String name) {
        assertThat(cookies(response, name))
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains(name + "=;")
                        .contains("Max-Age=0")
                        .doesNotContain("Domain="));
    }

    private static void assertCsrfClearedFromBothDomains(
            String name,
            CookieClearAction clearAction) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        clearAction.clear(response);

        List<String> cookies = cookies(response, name);
        assertThat(cookies).hasSize(2);
        assertThat(cookies).allSatisfy(cookie -> assertThat(cookie).contains("Max-Age=0"));
        assertThat(cookies).anySatisfy(cookie -> assertThat(cookie)
                .contains("Domain=niko000o.site"));
        assertThat(cookies).anySatisfy(cookie -> assertThat(cookie)
                .doesNotContain("Domain="));
    }

    private static String requiredCookie(
            MockHttpServletResponse response,
            String name,
            String value) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(name + "=" + value))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> cookies(MockHttpServletResponse response, String name) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(name + "="))
                .toList();
    }

    @FunctionalInterface
    private interface CookieClearAction {
        void clear(MockHttpServletResponse response);
    }
}
