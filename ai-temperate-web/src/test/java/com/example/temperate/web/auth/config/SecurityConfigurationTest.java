package com.example.temperate.web.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 验证 H5 与 Android 安全过滤链、CSRF 仓库和 CORS 装配边界。
 */
class SecurityConfigurationTest {

    @Test
    void providesTheSingleDelegatingPasswordEncoderContract() throws Exception {
        Method factoryMethod = SecurityConfiguration.class.getDeclaredMethod("passwordEncoder");
        factoryMethod.trySetAccessible();
        PasswordEncoder encoder =
                (PasswordEncoder) factoryMethod.invoke(new SecurityConfiguration());

        String rawPassword = "test-password-only";
        String encoded = encoder.encode(rawPassword);
        String secondEncoded = encoder.encode(rawPassword);

        assertThat(encoded).startsWith("{bcrypt}");
        assertThat(secondEncoded).startsWith("{bcrypt}").isNotEqualTo(encoded);
        assertThat(encoded).isNotEqualTo(rawPassword);
        assertThat(encoder.matches(rawPassword, encoded)).isTrue();
        assertThat(encoder.matches(rawPassword, secondEncoded)).isTrue();
        assertThat(encoder.matches("different-password", encoded)).isFalse();
        assertThat(encoder.upgradeEncoding(encoded)).isFalse();

        String weakBcrypt = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("test-password-only");
        assertThat(encoder.upgradeEncoding(weakBcrypt)).isTrue();
    }

    @Test
    void configuresTheH5CsrfCookieAndHeaderContract() {
        CookieCsrfTokenRepository repository =
                new SecurityConfiguration().csrfTokenRepository(propertiesWithCookieDomain(""));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        CsrfToken token = repository.generateToken(request);
        repository.saveToken(token, request, response);

        assertThat(token.getHeaderName()).isEqualTo("X-CSRF-Token");
        assertThat(response.getCookie("XSRF-TOKEN"))
                .isNotNull()
                .satisfies(cookie -> {
                    assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
                    assertThat(cookie.isHttpOnly()).isFalse();
                });
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("XSRF-TOKEN=")
                .contains("Path=/")
                .contains("Secure")
                .doesNotContain("HttpOnly")
                .doesNotContain("Max-Age")
                .doesNotContain("Domain=");
    }

    @Test
    void configuresTheH5CsrfCookieDomainForPublicSubdomainSessions() {
        CookieCsrfTokenRepository repository =
                new SecurityConfiguration().csrfTokenRepository(
                        propertiesWithCookieDomain("niko000o.site"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        CsrfToken token = repository.generateToken(request);
        repository.saveToken(token, request, response);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("XSRF-TOKEN=")
                .contains("Domain=niko000o.site");
    }

    @Test
    void exposesAuthTraceAndCloudflareDiagnosticHeaders() {
        CorsConfigurationSource source = new SecurityConfiguration()
                .corsConfigurationSource(propertiesWithCookieDomain(""));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/auth/register/turnstile");

        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedHeaders())
                .contains(
                        "X-Turnstile-Attempt-Id",
                        "X-AI-Client-Request-Id",
                        "X-Refresh-Token");
        assertThat(configuration.getExposedHeaders())
                .contains(
                        "X-Trace-Id",
                        "X-AI-Generation-Id",
                        "X-New-Access-Token",
                        "X-Session-Renewed",
                        "CF-Ray",
                        "cf-mitigated");
    }

    @Test
    void matchesOnlyTheConfiguredVoiceWebSocketPathAcrossServletContexts() {
        MockHttpServletRequest rootGet =
                new MockHttpServletRequest("GET", "/ws/voice");
        MockHttpServletRequest rootPost =
                new MockHttpServletRequest("POST", "/ws/voice");
        MockHttpServletRequest contextRequest =
                new MockHttpServletRequest("GET", "/app/ws/voice");
        contextRequest.setContextPath("/app");

        assertThat(SecurityConfiguration.isVoiceWebSocketRequest(
                rootGet, "/ws/voice")).isTrue();
        assertThat(SecurityConfiguration.isVoiceWebSocketRequest(
                rootPost, "/ws/voice")).isTrue();
        assertThat(SecurityConfiguration.isVoiceWebSocketRequest(
                contextRequest, "/ws/voice")).isTrue();

        for (String uri : List.of(
                "/ws/voice/",
                "/ws/voice/extra",
                "/api/ws/voice",
                "/ws%2Fvoice",
                "/api/health")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            assertThat(SecurityConfiguration.isVoiceWebSocketRequest(
                    request, "/ws/voice"))
                    .as("only the exact configured voice path is isolated: %s", uri)
                    .isFalse();
        }
    }

    @Test
    void ordinaryH5RequestsStillResolveTheDeferredCsrfToken() {
        SpaCsrfTokenRequestHandler handler = new SpaCsrfTokenRequestHandler();
        AtomicInteger resolutions = new AtomicInteger();

        handler.handle(
                new MockHttpServletRequest("GET", "/api/health"),
                new MockHttpServletResponse(),
                () -> {
                    resolutions.incrementAndGet();
                    return new DefaultCsrfToken(
                            "X-CSRF-Token", "_csrf", "test-csrf-token");
                });

        assertThat(resolutions).hasValue(1);
    }

    private static AuthSecurityProperties propertiesWithCookieDomain(String domain) {
        AuthSecurityProperties properties = mock(AuthSecurityProperties.class);
        when(properties.cors()).thenReturn(
                new AuthSecurityProperties.Cors(List.of("https://niko000o.site")));
        AuthSecurityProperties.CookieSettings csrf = new AuthSecurityProperties.CookieSettings(
                true, false, AuthSecurityProperties.SameSite.STRICT, "/");
        when(properties.cookies()).thenReturn(new AuthSecurityProperties.Cookies(
                new AuthSecurityProperties.CookieSettings(
                        true, true, AuthSecurityProperties.SameSite.STRICT, "/api"),
                new AuthSecurityProperties.CookieSettings(
                        true, true, AuthSecurityProperties.SameSite.STRICT, "/api"),
                csrf,
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
        return properties;
    }
}
