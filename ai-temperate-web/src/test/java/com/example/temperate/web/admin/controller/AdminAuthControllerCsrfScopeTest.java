package com.example.temperate.web.admin.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.AdminConfigurationService;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.admin.login.AdminLoginService;
import com.example.temperate.service.admin.registration.AdminRegistrationService;
import com.example.temperate.service.admin.session.AdminSessionService;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.admin.security.AdminClientPlatformResolver;
import com.example.temperate.web.admin.security.AdminH5CsrfCookieScopeValidator;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import com.example.temperate.web.admin.transport.AdminCookieWriter;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import com.example.temperate.web.edgeproxy.TrustedExternalHostResolver;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证管理员 Controller 在创建 Flow 或调用 hCaptcha 服务前拒绝不可读的 H5 CSRF Cookie 作用域。
 */
class AdminAuthControllerCsrfScopeTest {

    @Test
    void rejectsRegistrationStartBeforeCreatingRedisFlow() {
        Fixture fixture = fixture();

        assertConfigurationError(() -> fixture.controller().startRegistration(
                new AdminAuthController.RegistrationStartRequest(
                        "admin@example.test", "US", "+12025550123"),
                "00000000-0000-4000-8000-000000000001",
                "H5",
                crossHostRequest(),
                new MockHttpServletResponse()));

        verifyNoInteractions(fixture.registrationService());
    }

    @Test
    void rejectsHcaptchaSubmissionBeforeCallingSiteverifyService() {
        Fixture fixture = fixture();

        assertConfigurationError(() -> fixture.controller().verifyRegistrationHcaptcha(
                new AdminAuthController.HcaptchaRequest("test-hcaptcha-response"),
                null,
                null,
                "00000000-0000-4000-8000-000000000001",
                "H5",
                crossHostRequest(),
                new MockHttpServletResponse()));

        verifyNoInteractions(fixture.registrationService());
    }

    private static Fixture fixture() {
        AdminProperties properties = AdminProperties.testDefaults(
                Path.of("target/admin-auth-controller-csrf-scope-test/complete.yaml"));
        AdminRegistrationService registrationService = mock(AdminRegistrationService.class);
        AdminClientPlatformResolver platformResolver = new AdminClientPlatformResolver();
        AdminH5CsrfCookieScopeValidator validator =
                new AdminH5CsrfCookieScopeValidator(
                        properties,
                        platformResolver,
                        new TrustedExternalHostResolver());
        AdminAuthController controller = new AdminAuthController(
                mock(AdminConfigurationService.class),
                registrationService,
                mock(AdminLoginService.class),
                mock(AdminSessionService.class),
                mock(AdminCookieWriter.class),
                mock(RegistrationTokenGenerator.class),
                mock(TrustedClientIpResolver.class),
                properties,
                platformResolver,
                validator,
                mock(PreAuthService.class),
                mock(PreAuthTransport.class),
                mock(RiskRequestContextResolver.class),
                mock(NetworkRiskProperties.class));
        return new Fixture(controller, registrationService);
    }

    private static MockHttpServletRequest crossHostRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("api.niko000o.site");
        request.addHeader("Origin", "https://admin.example.test");
        return request;
    }

    private static void assertConfigurationError(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo(AdminErrorCode.ADMIN_CSRF_CONFIGURATION_INVALID));
    }

    private record Fixture(
            AdminAuthController controller,
            AdminRegistrationService registrationService) {
    }
}
