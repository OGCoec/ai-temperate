package com.example.temperate.web.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.admin.config.AdminConfigurationService;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.admin.login.AdminLoginStartResult;
import com.example.temperate.service.admin.login.AdminLoginService;
import com.example.temperate.service.admin.registration.AdminRegistrationService;
import com.example.temperate.service.admin.session.AdminSessionIssue;
import com.example.temperate.service.admin.session.AdminSessionProfile;
import com.example.temperate.service.admin.session.AdminSessionService;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import com.example.temperate.service.registration.dto.result.RegistrationStartResult;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.admin.security.AdminClientPlatformResolver;
import com.example.temperate.web.admin.security.AdminH5CsrfCookieScopeValidator;
import com.example.temperate.web.admin.security.AdminSessionAuthenticationInterceptor;
import com.example.temperate.web.admin.transport.AdminCookieWriter;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.webrtc.WebRtcVerificationTransport;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * 验证管理员 Flow 的平台化 Cookie 交付、服务端到期时间响应，以及退出后的 Cookie 清理顺序。
 *
 * <p>职责边界：测试固定 Controller 的传输编排，不替代服务层对 Flow 或管理员会话有效期的校验。</p>
 */
class AdminAuthControllerCookieTest {

    private static final Instant FLOW_EXPIRES_AT =
            Instant.parse("2026-08-04T13:10:00Z");

    private AdminRegistrationService registrationService;
    private AdminLoginService loginService;
    private AdminSessionService sessionService;
    private AdminCookieWriter cookieWriter;
    private PreAuthService preAuthService;
    private PreAuthTransport preAuthTransport;
    private RiskRequestContextResolver riskContextResolver;
    private NetworkRiskProperties networkRiskProperties;
    private AdminAuthController controller;

    @BeforeEach
    void setUp() {
        registrationService = mock(AdminRegistrationService.class);
        loginService = mock(AdminLoginService.class);
        sessionService = mock(AdminSessionService.class);
        cookieWriter = mock(AdminCookieWriter.class);
        preAuthService = mock(PreAuthService.class);
        preAuthTransport = mock(PreAuthTransport.class);
        riskContextResolver = mock(RiskRequestContextResolver.class);
        networkRiskProperties = mock(NetworkRiskProperties.class);
        TrustedClientIpResolver clientIpResolver = mock(TrustedClientIpResolver.class);
        when(clientIpResolver.resolve(any())).thenReturn(Optional.of("203.0.113.10"));
        AdminProperties properties = AdminProperties.testDefaults(
                Path.of("target/admin-auth-controller-cookie-test/complete.yaml"));
        controller = new AdminAuthController(
                mock(AdminConfigurationService.class),
                registrationService,
                loginService,
                sessionService,
                cookieWriter,
                mock(RegistrationTokenGenerator.class),
                clientIpResolver,
                properties,
                new AdminClientPlatformResolver(),
                mock(AdminH5CsrfCookieScopeValidator.class),
                preAuthService,
                preAuthTransport,
                riskContextResolver,
                networkRiskProperties,
                new WebRtcVerificationTransport());
    }

    @Test
    void registrationStartKeepsServerExpiryWhileWritingH5SessionFlowCookies() {
        when(registrationService.start(any())).thenReturn(new RegistrationStartResult(
                "register-token",
                "register-csrf",
                "register-challenge",
                FLOW_EXPIRES_AT));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminAuthController.RegistrationStartResponse result = controller.startRegistration(
                new AdminAuthController.RegistrationStartRequest(
                        "admin@example.test", "US", "+12025550123"),
                "00000000-0000-4000-8000-000000000001",
                "H5",
                new MockHttpServletRequest(),
                response);

        verify(cookieWriter).writeRegistration(
                response,
                "register-token",
                "register-csrf",
                "register-challenge");
        assertThat(result.registerToken()).isNull();
        assertThat(result.flowCsrf()).isNull();
        assertThat(result.expiresAt()).isEqualTo(FLOW_EXPIRES_AT);
    }

    @Test
    void loginStartKeepsServerExpiryWhileWritingH5SessionFlowCookies() {
        when(loginService.start(any(), any())).thenReturn(new AdminLoginStartResult(
                "login-token",
                "login-csrf",
                "login-challenge",
                FLOW_EXPIRES_AT));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminAuthController.LoginStartResponse result = controller.startLogin(
                "00000000-0000-4000-8000-000000000001",
                "H5",
                new MockHttpServletRequest(),
                response);

        verify(cookieWriter).writeLogin(
                response,
                "login-token",
                "login-csrf",
                "login-challenge");
        assertThat(result.loginFlowToken()).isNull();
        assertThat(result.flowCsrf()).isNull();
        assertThat(result.expiresAt()).isEqualTo(FLOW_EXPIRES_AT);
    }

    @Test
    void androidStartsKeepFlowMaterialsInResponsesWithoutWritingH5Cookies() {
        when(registrationService.start(any())).thenReturn(new RegistrationStartResult(
                "register-token",
                "register-csrf",
                "register-challenge",
                FLOW_EXPIRES_AT));
        when(loginService.start(any(), any())).thenReturn(new AdminLoginStartResult(
                "login-token",
                "login-csrf",
                "login-challenge",
                FLOW_EXPIRES_AT));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "ANDROID");

        AdminAuthController.RegistrationStartResponse registration =
                controller.startRegistration(
                        new AdminAuthController.RegistrationStartRequest(
                                "admin@example.test", "US", "+12025550123"),
                        "00000000-0000-4000-8000-000000000001",
                        "ANDROID",
                        request,
                        new MockHttpServletResponse());
        AdminAuthController.LoginStartResponse login = controller.startLogin(
                "00000000-0000-4000-8000-000000000001",
                "ANDROID",
                request,
                new MockHttpServletResponse());

        verify(cookieWriter, never()).writeRegistration(any(), any(), any(), any());
        verify(cookieWriter, never()).writeLogin(any(), any(), any(), any());
        assertThat(registration.registerToken()).isEqualTo("register-token");
        assertThat(registration.flowCsrf()).isEqualTo("register-csrf");
        assertThat(registration.expiresAt()).isEqualTo(FLOW_EXPIRES_AT);
        assertThat(login.loginFlowToken()).isEqualTo("login-token");
        assertThat(login.flowCsrf()).isEqualTo("login-csrf");
        assertThat(login.expiresAt()).isEqualTo(FLOW_EXPIRES_AT);
    }

    @Test
    void adminLoginRotationPublishesTheSameBackgroundVerificationHeaders() {
        Instant expiresAt = Instant.parse("2026-08-04T14:00:00Z");
        when(networkRiskProperties.mode()).thenReturn(NetworkRiskMode.ENFORCE);
        when(riskContextResolver.resolve(any())).thenReturn(Optional.of(
                new TrustedNetworkObservation(
                        "203.0.113.10",
                        "US",
                        64500L,
                        null,
                        null,
                        Instant.parse("2026-08-04T13:00:00Z"))));
        when(loginService.complete(any())).thenReturn(Mono.just(new AdminSessionIssue(
                "admin-session-token",
                new AdminSessionProfile(
                        "admin@example.test",
                        "US",
                        "+12025550123",
                        expiresAt))));
        when(preAuthService.promoteAuthenticated(any(), any(), any(), any()))
                .thenReturn(new PreAuthIssue(
                        "admin-preauth",
                        expiresAt,
                        PreAuthWebRtcPhase.REQUIRED,
                        6L));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "ANDROID");
        request.addHeader(AdminAuthController.FLOW_CSRF_HEADER, "flow-csrf");
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                mock(PreAuthAccess.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.completeLogin(
                        new AdminAuthController.LoginRequest(
                                "admin@example.test",
                                "US",
                                "+12025550123",
                                "test-password",
                                "hcaptcha-token"),
                        "flow-token",
                        "challenge-id",
                        "00000000-0000-4000-8000-000000000001",
                        "ANDROID",
                        request,
                        response)
                .toFuture()
                .join();

        assertThat(response.getHeader(WebRtcVerificationTransport.STATE_HEADER))
                .isEqualTo("REQUIRED");
        assertThat(response.getHeader(WebRtcVerificationTransport.GENERATION_HEADER))
                .isEqualTo("6");
    }

    @Test
    void logoutClearsCookiesOnlyAfterServerSideRevocation() {
        MockHttpServletRequest request = requestWithSession("admin-session-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(preAuthTransport.read(request, RiskScope.ADMIN)).thenReturn("admin-preauth");

        controller.logout(request, response);

        InOrder order = inOrder(sessionService, preAuthService, cookieWriter, preAuthTransport);
        order.verify(sessionService).logout("admin-session-token");
        order.verify(preAuthTransport).read(request, RiskScope.ADMIN);
        order.verify(preAuthService).revoke(RiskScope.ADMIN, "admin-preauth");
        order.verify(cookieWriter).clearSession(response);
        order.verify(preAuthTransport).clearCookie(response, RiskScope.ADMIN);
    }

    @Test
    void logoutAllClearsCookiesOnlyAfterServerSideRevocation() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(preAuthTransport.read(request, RiskScope.ADMIN)).thenReturn("admin-preauth");

        controller.logoutAll(request, response);

        InOrder order = inOrder(sessionService, preAuthService, cookieWriter, preAuthTransport);
        order.verify(sessionService).logoutAll();
        order.verify(preAuthTransport).read(request, RiskScope.ADMIN);
        order.verify(preAuthService).revoke(RiskScope.ADMIN, "admin-preauth");
        order.verify(cookieWriter).clearSession(response);
        order.verify(preAuthTransport).clearCookie(response, RiskScope.ADMIN);
    }

    @Test
    void failedServerSideLogoutDoesNotPretendBrowserSessionWasCleared() {
        MockHttpServletRequest request = requestWithSession("admin-session-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        doThrow(new IllegalStateException("simulated session store failure"))
                .when(sessionService)
                .logout("admin-session-token");

        assertThatThrownBy(() -> controller.logout(request, response))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(preAuthService);
        verify(cookieWriter, never()).clearSession(response);
        verify(preAuthTransport, never()).clearCookie(response, RiskScope.ADMIN);
    }

    private static MockHttpServletRequest requestWithSession(String rawToken) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                AdminSessionAuthenticationInterceptor.RAW_TOKEN_ATTRIBUTE,
                rawToken);
        return request;
    }
}
