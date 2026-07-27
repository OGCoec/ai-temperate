package com.example.temperate.web.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.admin.session.AdminSessionProfile;
import com.example.temperate.service.admin.session.AdminSessionService;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.admin.transport.AdminCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.PreAuthTransport;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 验证管理员会话 MVC 拦截器先确认会话凭据，再复用上游已验证 PreAuth 完成原子绑定与续期。
 *
 * <p>登录页没有管理员会话属于正常未认证状态；这些测试防止它再次被误分类为 PreAuth 失效并触发前端
 * 第二轮 WebRTC 初始化，同时固定请求结束后的线程安全上下文清理。</p>
 */
class AdminSessionAuthenticationInterceptorTest {

    private static final String ADMIN_ORIGIN = "https://admin.example.test";
    private static final String SESSION_PATH = "/api/admin/auth/session/bootstrap";
    private static final String DEVICE_ID = "test-device-installation-id";
    private static final String SESSION_TOKEN = "test-admin-session-token";
    private static final String PREAUTH_TOKEN = "test-admin-preauth-token";

    private AdminSessionService sessionService;
    private AdminCookieWriter cookieWriter;
    private RegistrationTokenGenerator tokenGenerator;
    private AdminClientPlatformResolver platformResolver;
    private AdminH5CsrfCookieScopeValidator csrfCookieScopeValidator;
    private NetworkRiskProperties networkRiskProperties;
    private PreAuthService preAuthService;
    private PreAuthTransport preAuthTransport;
    private AdminSessionAuthenticationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        sessionService = mock(AdminSessionService.class);
        cookieWriter = mock(AdminCookieWriter.class);
        tokenGenerator = mock(RegistrationTokenGenerator.class);
        platformResolver = mock(AdminClientPlatformResolver.class);
        csrfCookieScopeValidator = mock(AdminH5CsrfCookieScopeValidator.class);
        networkRiskProperties = mock(NetworkRiskProperties.class);
        preAuthService = mock(PreAuthService.class);
        preAuthTransport = mock(PreAuthTransport.class);

        interceptor = new AdminSessionAuthenticationInterceptor(
                sessionService,
                cookieWriter,
                tokenGenerator,
                AdminProperties.testDefaults(Path.of("admin-interceptor-test.json")),
                platformResolver,
                csrfCookieScopeValidator,
                networkRiskProperties,
                preAuthService,
                preAuthTransport);
        when(networkRiskProperties.mode()).thenReturn(NetworkRiskMode.ENFORCE);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingH5SessionIsClassifiedBeforePreAuthBinding() {
        MockHttpServletRequest request = h5Request();
        when(platformResolver.resolve(request)).thenReturn(AuthClientPlatform.H5);

        AdminException exception = rejected(request);

        assertThat(exception.code()).isEqualTo(AdminErrorCode.ADMIN_SESSION_INVALID);
        assertThat(exception.clearFlow()).isFalse();
        assertThat(exception.clearSession()).isTrue();
        verifyNoInteractions(preAuthTransport, preAuthService, sessionService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingAndroidBearerRemainsAnInvalidAdministratorSession() {
        MockHttpServletRequest request = androidRequest();
        when(platformResolver.resolve(request)).thenReturn(AuthClientPlatform.ANDROID);

        AdminException exception = rejected(request);

        assertThat(exception.code()).isEqualTo(AdminErrorCode.ADMIN_SESSION_INVALID);
        assertThat(exception.clearSession()).isTrue();
        verifyNoInteractions(preAuthTransport, preAuthService, sessionService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void publicAndLoginFlowPathsBypassAdministratorSessionAuthentication() {
        for (String path : List.of(
                "/api/admin/auth/state",
                "/api/admin/_edge/pre-auth",
                "/api/admin/_edge/risk-challenge",
                "/api/admin/_edge/webrtc/start",
                "/api/admin/_edge/webrtc/report",
                "/api/admin/auth/phone-country",
                "/api/admin/auth/hcaptcha/config",
                "/api/admin/auth/hcaptcha/page",
                "/api/admin/auth/hcaptcha/page.css",
                "/api/admin/auth/hcaptcha/page.js",
                "/api/admin/auth/register",
                "/api/admin/auth/register/start",
                "/api/admin/auth/login",
                "/api/admin/auth/login/complete")) {
            boolean accepted = interceptor.preHandle(
                    new MockHttpServletRequest("GET", path),
                    new MockHttpServletResponse(),
                    new Object());

            assertThat(accepted).isTrue();
        }

        verifyNoInteractions(
                platformResolver,
                csrfCookieScopeValidator,
                cookieWriter,
                preAuthTransport,
                preAuthService,
                sessionService);
    }

    @Test
    void presentSessionWithoutVerifiedPreAuthRemainsPreAuthRequiredInEnforceMode() {
        MockHttpServletRequest request = h5Request();
        when(platformResolver.resolve(request)).thenReturn(AuthClientPlatform.H5);
        when(cookieWriter.sessionToken(request)).thenReturn(SESSION_TOKEN);
        when(preAuthTransport.read(request, RiskScope.ADMIN)).thenReturn(PREAUTH_TOKEN);

        AdminException exception = rejected(request);

        assertThat(exception.code()).isEqualTo(AdminErrorCode.ADMIN_PREAUTH_REQUIRED);
        assertThat(exception.clearSession()).isFalse();
        verify(preAuthService, never()).resolve(any(), any(), any());
        verify(sessionService, never()).touch(any(), any());
        verify(sessionService, never()).touch(any(), any(), any());
    }

    @Test
    void presentSessionAndVerifiedAnonymousPreAuthUseAtomicRenewal() {
        MockHttpServletRequest request = h5Request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        PreAuthAccess verifiedAccess = mock(PreAuthAccess.class);
        PreAuthAccess refreshedAccess = mock(PreAuthAccess.class);
        PreAuthSessionBinding binding = mock(PreAuthSessionBinding.class);
        HmacIdentifier tokenDigest =
                HmacIdentifier.fromProtectedValue("A".repeat(43));
        AdminSessionProfile profile = profile();
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                verifiedAccess);
        when(platformResolver.resolve(request)).thenReturn(AuthClientPlatform.H5);
        when(cookieWriter.sessionToken(request)).thenReturn(SESSION_TOKEN);
        when(preAuthTransport.read(request, RiskScope.ADMIN)).thenReturn(PREAUTH_TOKEN);
        when(preAuthService.requireSessionBinding(
                verifiedAccess,
                RiskScope.ADMIN,
                RiskSessionType.ADMIN_SESSION,
                SESSION_TOKEN))
                .thenReturn(binding);
        when(binding.tokenDigest()).thenReturn(tokenDigest);
        when(refreshedAccess.tokenDigest()).thenReturn(tokenDigest);
        when(sessionService.touch(SESSION_TOKEN, DEVICE_ID, binding)).thenReturn(profile);
        when(preAuthService.resolve(RiskScope.ADMIN, PREAUTH_TOKEN, DEVICE_ID))
                .thenReturn(Optional.of(refreshedAccess));
        when(cookieWriter.csrfToken(request)).thenReturn("test-admin-csrf");

        boolean accepted = interceptor.preHandle(request, response, new Object());

        assertThat(accepted).isTrue();
        assertThat(request.getAttribute(
                AdminSessionAuthenticationInterceptor.RAW_TOKEN_ATTRIBUTE))
                .isEqualTo(SESSION_TOKEN);
        assertThat(request.getAttribute(
                AdminSessionAuthenticationInterceptor.PROFILE_ATTRIBUTE))
                .isSameAs(profile);
        assertThat(request.getAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE))
                .isSameAs(refreshedAccess);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNotNull();
        verify(sessionService).touch(SESSION_TOKEN, DEVICE_ID, binding);
        verify(preAuthService, never()).touch(any(), any());
        verify(cookieWriter).refreshSession(
                response,
                SESSION_TOKEN,
                "test-admin-csrf",
                profile.expiresAt());

        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void controllerFailureAlsoClearsSecurityContextDuringCompletion() {
        Authentication authentication = mock(Authentication.class);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        interceptor.afterCompletion(
                h5Request(),
                new MockHttpServletResponse(),
                new Object(),
                new IllegalStateException("controller failed"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void preAuthBoundToAnotherSessionRemainsPreAuthRequired() {
        MockHttpServletRequest request = h5Request();
        PreAuthAccess access = mock(PreAuthAccess.class);
        request.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);
        when(platformResolver.resolve(request)).thenReturn(AuthClientPlatform.H5);
        when(cookieWriter.sessionToken(request)).thenReturn(SESSION_TOKEN);
        when(preAuthTransport.read(request, RiskScope.ADMIN)).thenReturn(PREAUTH_TOKEN);
        when(preAuthService.requireSessionBinding(
                access,
                RiskScope.ADMIN,
                RiskSessionType.ADMIN_SESSION,
                SESSION_TOKEN))
                .thenThrow(new IllegalArgumentException("binding mismatch"));

        AdminException exception = rejected(request);

        assertThat(exception.code()).isEqualTo(AdminErrorCode.ADMIN_PREAUTH_REQUIRED);
        assertThat(exception.clearSession()).isFalse();
        verify(sessionService, never()).touch(any(), any());
        verify(sessionService, never()).touch(any(), any(), any());
    }

    @Test
    void runtimeFailureIsSanitizedAndClearsSecurityContext() {
        MockHttpServletRequest request = h5Request();
        PreAuthAccess access = mock(PreAuthAccess.class);
        PreAuthSessionBinding binding = mock(PreAuthSessionBinding.class);
        request.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);
        when(platformResolver.resolve(request)).thenReturn(AuthClientPlatform.H5);
        when(cookieWriter.sessionToken(request)).thenReturn(SESSION_TOKEN);
        when(preAuthTransport.read(request, RiskScope.ADMIN)).thenReturn(PREAUTH_TOKEN);
        when(preAuthService.requireSessionBinding(
                access,
                RiskScope.ADMIN,
                RiskSessionType.ADMIN_SESSION,
                SESSION_TOKEN))
                .thenReturn(binding);
        when(sessionService.touch(SESSION_TOKEN, DEVICE_ID, binding))
                .thenThrow(new IllegalStateException("redis unavailable"));

        AdminException exception = rejected(request);

        assertThat(exception.code()).isEqualTo(AdminErrorCode.ADMIN_SESSION_INVALID);
        assertThat(exception.clearSession()).isTrue();
        assertThat(exception.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private AdminException rejected(MockHttpServletRequest request) {
        try {
            interceptor.preHandle(
                    request,
                    new MockHttpServletResponse(),
                    new Object());
            throw new AssertionError("Expected administrator request to be rejected.");
        } catch (AdminException exception) {
            return exception;
        }
    }

    private static AdminSessionProfile profile() {
        return new AdminSessionProfile(
                "admin@example.test",
                "US",
                "+12025550123",
                Instant.parse("2026-07-26T20:00:00Z"));
    }

    private static MockHttpServletRequest h5Request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", SESSION_PATH);
        request.addHeader("Origin", ADMIN_ORIGIN);
        request.addHeader("X-Client-Platform", "H5");
        request.addHeader("X-Device-Installation-Id", DEVICE_ID);
        return request;
    }

    private static MockHttpServletRequest androidRequest() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", SESSION_PATH);
        request.addHeader("X-Client-Platform", "ANDROID");
        request.addHeader("X-Device-Installation-Id", DEVICE_ID);
        return request;
    }
}
