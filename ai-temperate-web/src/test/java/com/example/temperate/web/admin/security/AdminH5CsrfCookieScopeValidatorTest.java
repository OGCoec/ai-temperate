package com.example.temperate.web.admin.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.web.edgeproxy.TrustedExternalHostResolver;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 验证管理员 H5 在可信 Worker Host 下使用 Host-only CSRF，并保留迁移期父域校验。
 *
 * <p>这些测试只检查 Cookie 作用域边界，不发起网络请求，也不接触 Cookie、Flow 或 hCaptcha
 * 的真实值。</p>
 */
class AdminH5CsrfCookieScopeValidatorTest {

    @Test
    void acceptsDedicatedParentDomainCoveringAdministratorAndApiHosts() {
        AdminH5CsrfCookieScopeValidator validator = validator(
                "",
                "niko000o.site",
                List.of("https://admin.niko000o.site"));

        assertThatCode(() -> validator.requireFlowReadable(request(
                "https://admin.niko000o.site", "api.niko000o.site")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCrossHostH5WhenDedicatedCsrfDomainIsEmpty() {
        AdminH5CsrfCookieScopeValidator validator = validator(
                "",
                "",
                List.of("https://admin.niko000o.site"));

        assertFlowConfigurationError(() -> validator.requireFlowReadable(request(
                "https://admin.niko000o.site", "api.niko000o.site")));
    }

    @Test
    void rejectsDomainThatDoesNotCoverBothHostsOrUsesAFalseSuffixBoundary() {
        for (String domain : List.of("admin.niko000o.site", "evilniko000o.site")) {
            AdminH5CsrfCookieScopeValidator validator = validator(
                    "",
                    domain,
                    List.of("https://admin.niko000o.site"));

            assertFlowConfigurationError(() -> validator.requireFlowReadable(request(
                    "https://admin.niko000o.site", "api.niko000o.site")));
        }
    }

    @Test
    void acceptsSameHostLocalDevelopmentWithoutCookieDomain() {
        AdminH5CsrfCookieScopeValidator validator = validator(
                "",
                "",
                List.of("https://localhost:5173"));

        assertThatCode(() -> validator.requireFlowReadable(request(
                "https://localhost:5173", "localhost")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsHostOnlyCookieWhenVerifiedWorkerHostMatchesAdministratorOrigin() {
        AdminH5CsrfCookieScopeValidator validator = validator(
                "",
                "",
                List.of("https://admin.niko000o.site"));
        MockHttpServletRequest request = request(
                "https://admin.niko000o.site",
                "api.niko000o.site");
        request.setAttribute(
                TrustedExternalHostResolver.VERIFIED_EXTERNAL_HOST_ATTRIBUTE,
                "admin.niko000o.site");

        assertThatCode(() -> validator.requireFlowReadable(request))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsBrowserCookieScopeValidationForAndroid() {
        AdminH5CsrfCookieScopeValidator validator = validator(
                "",
                "",
                List.of("https://admin.niko000o.site"));
        MockHttpServletRequest request = request(null, "api.niko000o.site");
        request.addHeader("X-Client-Platform", "ANDROID");

        assertThatCode(() -> validator.requireFlowReadable(request))
                .doesNotThrowAnyException();
    }

    @Test
    void flowConfigurationFailureClearsOnlyFlowCookies() {
        AdminH5CsrfCookieScopeValidator validator = validator(
                "",
                "",
                List.of("https://admin.niko000o.site"));

        assertFlowConfigurationError(() -> validator.requireFlowReadable(request(
                "https://admin.niko000o.site", "api.niko000o.site")));
    }

    @Test
    void sessionConfigurationFailureClearsOnlySessionCookies() {
        AdminH5CsrfCookieScopeValidator validator = validator(
                "",
                "",
                List.of("https://admin.niko000o.site"));

        assertThatThrownBy(() -> validator.requireSessionReadable(request(
                "https://admin.niko000o.site", "api.niko000o.site")))
                .isInstanceOfSatisfying(AdminException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.code())
                            .isEqualTo(AdminErrorCode.ADMIN_CSRF_CONFIGURATION_INVALID);
                    org.assertj.core.api.Assertions.assertThat(exception.clearFlow()).isFalse();
                    org.assertj.core.api.Assertions.assertThat(exception.clearSession()).isTrue();
                });
    }

    private static AdminH5CsrfCookieScopeValidator validator(
            String sensitiveDomain,
            String csrfDomain,
            List<String> allowedOrigins) {
        AdminProperties defaults = AdminProperties.testDefaults(
                Path.of("target/admin-h5-csrf-cookie-scope-test/complete.yaml"));
        AdminProperties.Cookies cookies = defaults.cookies();
        AdminProperties properties = new AdminProperties(
                defaults.configPath(),
                defaults.sessionHmacSecretBase64(),
                defaults.sessionTtl(),
                defaults.maxSessions(),
                defaults.registrationFlowTtl(),
                defaults.loginFlowTtl(),
                allowedOrigins,
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
        return new AdminH5CsrfCookieScopeValidator(
                properties,
                new AdminClientPlatformResolver(),
                new TrustedExternalHostResolver());
    }

    private static MockHttpServletRequest request(String origin, String serverName) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName(serverName);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    private static void assertFlowConfigurationError(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(AdminException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.code())
                            .isEqualTo(AdminErrorCode.ADMIN_CSRF_CONFIGURATION_INVALID);
                    org.assertj.core.api.Assertions.assertThat(exception.clearFlow()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(exception.clearSession()).isFalse();
                });
    }
}
