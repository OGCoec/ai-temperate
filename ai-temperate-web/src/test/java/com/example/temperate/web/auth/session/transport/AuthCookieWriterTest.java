package com.example.temperate.web.auth.session.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证三类认证 Cookie 的属性、有效期、共享域名和新旧路径清理行为。
 */
class AuthCookieWriterTest {

    private static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");

    private AuthCookieWriter writer;

    @BeforeEach
    void setUp() {
        writer = writerWithDomain("");
    }

    @Test
    void writesTheBrowserSessionCookiesAndExpiresTheLegacyRefreshPath() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.writeSession(
                response,
                "access-value",
                "refresh-value",
                "csrf-value",
                NOW.plus(Duration.ofHours(3)));

        List<String> cookies = List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE));
        assertThat(cookie(cookies, AuthCookieWriter.ACCESS_COOKIE))
                .contains("access_token=access-value")
                .contains("Path=/api")
                .contains("Max-Age=600")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Domain=");
        assertThat(cookie(cookies, AuthCookieWriter.REFRESH_COOKIE))
                .contains("refresh_token=refresh-value")
                .contains("Path=/api")
                .contains("Max-Age=10800")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Domain=");
        assertThat(cookies).anySatisfy(value -> assertThat(value)
                .startsWith(AuthCookieWriter.REFRESH_COOKIE + "=")
                .contains("Path=/api/auth/session")
                .contains("Max-Age=0"));
        assertThat(cookie(cookies, AuthCookieWriter.CSRF_COOKIE))
                .contains("XSRF-TOKEN=csrf-value")
                .contains("Path=/")
                .contains("Secure")
                .contains("SameSite=Strict")
                .doesNotContain("HttpOnly")
                .doesNotContain("Max-Age")
                .doesNotContain("Domain=");
        assertThat(cookie(cookies, AuthCookieWriter.LEGACY_REFRESH_COOKIE))
                .contains("rt=")
                .contains("Path=/")
                .contains("Max-Age=0");
    }

    @Test
    void writesConfiguredCookieDomainForPublicSubdomainSessions() {
        writer = writerWithDomain("niko000o.site");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.writeSession(
                response,
                "access-value",
                "refresh-value",
                "csrf-value",
                NOW.plus(Duration.ofHours(3)));

        List<String> cookies = List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE));
        assertThat(cookie(cookies, AuthCookieWriter.ACCESS_COOKIE))
                .contains("Domain=niko000o.site");
        assertThat(cookie(cookies, AuthCookieWriter.REFRESH_COOKIE))
                .contains("Domain=niko000o.site");
        assertThat(cookie(cookies, AuthCookieWriter.CSRF_COOKIE))
                .contains("Domain=niko000o.site");
        assertThat(cookie(cookies, AuthCookieWriter.LEGACY_REFRESH_COOKIE))
                .contains("Domain=niko000o.site");
    }

    @Test
    void clearsEveryCookieUsingItsOriginalPath() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.clearSession(response);

        List<String> cookies = List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE));
        assertThat(cookie(cookies, AuthCookieWriter.ACCESS_COOKIE))
                .contains("Path=/api")
                .contains("Max-Age=0");
        assertThat(cookie(cookies, AuthCookieWriter.REFRESH_COOKIE))
                .contains("Path=/api")
                .contains("Max-Age=0");
        assertThat(cookies).anySatisfy(value -> assertThat(value)
                .startsWith(AuthCookieWriter.REFRESH_COOKIE + "=")
                .contains("Path=/api/auth/session")
                .contains("Max-Age=0"));
        assertThat(cookie(cookies, AuthCookieWriter.CSRF_COOKIE))
                .contains("Path=/")
                .contains("Max-Age=0");
        assertThat(cookie(cookies, AuthCookieWriter.LEGACY_REFRESH_COOKIE))
                .contains("Path=/")
                .contains("Max-Age=0");
    }

    private static AuthCookieWriter writerWithDomain(String domain) {
        AuthSecurityProperties properties = mock(AuthSecurityProperties.class);
        when(properties.cookies()).thenReturn(new AuthSecurityProperties.Cookies(
                new AuthSecurityProperties.CookieSettings(
                        true, true, AuthSecurityProperties.SameSite.STRICT, "/api"),
                new AuthSecurityProperties.CookieSettings(
                        true, true, AuthSecurityProperties.SameSite.STRICT, "/api"),
                new AuthSecurityProperties.CookieSettings(
                        true, false, AuthSecurityProperties.SameSite.STRICT, "/"),
                new AuthSecurityProperties.CookieSettings(
                        true, true, AuthSecurityProperties.SameSite.STRICT,
                        "/api/auth/register"),
                new AuthSecurityProperties.CookieSettings(
                        true, true, AuthSecurityProperties.SameSite.STRICT,
                        "/api/auth/register"),
                new AuthSecurityProperties.CookieSettings(
                        true, true, AuthSecurityProperties.SameSite.STRICT,
                        "/api/auth/password-reset"),
                new AuthSecurityProperties.CookieSettings(
                        true, true, AuthSecurityProperties.SameSite.STRICT,
                        "/api/auth/password-reset/complete"),
                new AuthSecurityProperties.CookieSettings(
                        true, true, AuthSecurityProperties.SameSite.STRICT,
                        "/api/auth/login/totp"),
                domain));
        when(properties.ttl()).thenReturn(new AuthSecurityProperties.Ttl(
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                Duration.ofMinutes(10),
                Duration.ofSeconds(45)));
        return new AuthCookieWriter(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String cookie(List<String> cookies, String name) {
        return cookies.stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElseThrow();
    }
}
