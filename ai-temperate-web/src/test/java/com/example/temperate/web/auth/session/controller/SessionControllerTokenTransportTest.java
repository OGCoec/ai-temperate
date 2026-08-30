package com.example.temperate.web.auth.session.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.dto.command.LogoutCommand;
import com.example.temperate.service.auth.session.authentication.dto.command.SessionBootstrapCommand;
import com.example.temperate.service.auth.session.authentication.dto.result.SessionAuthenticationResult;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.auth.api.WebInvalidInputException;
import com.example.temperate.web.auth.interceptor.UserSessionAuthenticationInterceptor;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.webrtc.WebRtcVerificationTransport;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证显式刷新接口删除后，H5 恢复和双端退出会话仍保持严格的 Token 来源隔离。
 */
class SessionControllerTokenTransportTest {

    private static final Instant REFRESH_EXPIRES_AT =
            Instant.parse("2026-07-15T03:00:00Z");

    private SessionAuthenticationService service;
    private AuthCookieWriter cookieWriter;
    private PreAuthService preAuthService;
    private PreAuthTransport preAuthTransport;
    private NetworkRiskProperties networkRiskProperties;
    private SessionController controller;

    @BeforeEach
    void setUp() {
        service = mock(SessionAuthenticationService.class);
        cookieWriter = mock(AuthCookieWriter.class);
        preAuthService = mock(PreAuthService.class);
        preAuthTransport = mock(PreAuthTransport.class);
        networkRiskProperties = mock(NetworkRiskProperties.class);
        controller = new SessionController(
                service,
                cookieWriter,
                preAuthService,
                preAuthTransport,
                networkRiskProperties,
                new WebRtcVerificationTransport());
        when(service.bootstrap(any())).thenReturn(result());
        when(preAuthTransport.read(any(), any())).thenReturn("user-preauth");
        when(networkRiskProperties.mode()).thenReturn(NetworkRiskMode.ENFORCE);
    }

    @Test
    void noLongerExposesTheExplicitRefreshMethod() {
        assertThat(Arrays.stream(SessionController.class.getDeclaredMethods())
                .map(method -> method.getName())
                .toList())
                .doesNotContain("refresh");
    }

    @Test
    void h5BootstrapUsesOnlyCookiesAndRotatesTheBrowserSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(AuthCookieWriter.ACCESS_COOKIE, "browser-at"),
                new Cookie(AuthCookieWriter.REFRESH_COOKIE, "browser-rt"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        SessionController.SessionResponse response = controller.bootstrap(
                new SessionController.SessionRequest("ignored-body-rt"),
                "device-1",
                "H5",
                request,
                servletResponse);

        ArgumentCaptor<SessionBootstrapCommand> command =
                ArgumentCaptor.forClass(SessionBootstrapCommand.class);
        verify(service).bootstrap(command.capture());
        assertThat(command.getValue().getAccessToken()).isEqualTo("browser-at");
        assertThat(command.getValue().getRefreshToken()).isEqualTo("browser-rt");
        verify(cookieWriter).writeSession(
                servletResponse,
                "new-access-value",
                "browser-rt",
                "csrf-value");
        assertThat(response.accessToken()).isNull();
        assertThat(response.csrfToken()).isNull();
    }

    @Test
    void h5BootstrapWithoutRefreshCookieSkipsPreAuthBindingAndDelegatesMissingTokenToService() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                new PreAuthAccess(null, null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        SessionAuthenticationException expected = new SessionAuthenticationException(
                SessionAuthenticationErrorCode.REFRESH_TOKEN_REQUIRED,
                "Refresh token is required.",
                true);
        doThrow(expected).when(service).bootstrap(any(SessionBootstrapCommand.class));

        assertThatThrownBy(() -> controller.bootstrap(
                null,
                "device-1",
                "H5",
                request,
                response))
                .isSameAs(expected)
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(SessionAuthenticationErrorCode.REFRESH_TOKEN_REQUIRED);
                    assertThat(exception.clearCookies()).isTrue();
                });

        ArgumentCaptor<SessionBootstrapCommand> command =
                ArgumentCaptor.forClass(SessionBootstrapCommand.class);
        verify(service).bootstrap(command.capture());
        assertThat(command.getValue().getRefreshToken()).isNull();
        assertThat(request.getAttribute(
                UserSessionAuthenticationInterceptor.BINDING_ATTEMPTED_ATTRIBUTE))
                .isEqualTo(Boolean.FALSE);
        assertThat(request.getAttribute(
                UserSessionAuthenticationInterceptor.BINDING_RESULT_ATTRIBUTE))
                .isEqualTo("skipped_missing_refresh");
        verifyNoInteractions(preAuthService);
    }

    @Test
    void h5BootstrapPreservesCurrentBackgroundVerificationSignal() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(AuthCookieWriter.ACCESS_COOKIE, "browser-at"),
                new Cookie(AuthCookieWriter.REFRESH_COOKIE, "browser-rt"));
        PreAuthAccess access = mock(PreAuthAccess.class);
        PreAuthState state = mock(PreAuthState.class);
        PreAuthSessionBinding binding = mock(PreAuthSessionBinding.class);
        when(access.state()).thenReturn(state);
        when(state.webRtcPhase()).thenReturn(PreAuthWebRtcPhase.PENDING);
        when(state.webRtcGeneration()).thenReturn(9L);
        when(preAuthService.requireSessionBinding(any(), any(), any(), any()))
                .thenReturn(binding);
        when(service.bootstrap(any(), any())).thenReturn(result());
        request.setAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE, access);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.bootstrap(
                null,
                "device-1",
                "H5",
                request,
                response);

        verify(service).bootstrap(any(SessionBootstrapCommand.class), same(binding));
        assertThat(response.getHeader(WebRtcVerificationTransport.STATE_HEADER))
                .isEqualTo("PENDING");
        assertThat(response.getHeader(WebRtcVerificationTransport.GENERATION_HEADER))
                .isEqualTo("9");
    }

    @Test
    void h5BootstrapWithRefreshCookieKeepsRecoverablePreAuthFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookieWriter.REFRESH_COOKIE, "browser-rt"));
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                mock(PreAuthAccess.class));
        when(preAuthService.requireSessionBinding(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("simulated binding mismatch"));

        assertThatThrownBy(() -> controller.bootstrap(
                null,
                "device-1",
                "H5",
                request,
                new MockHttpServletResponse()))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(SessionAuthenticationErrorCode.PREAUTH_REQUIRED);
                    assertThat(exception.clearCookies()).isFalse();
                });

        assertThat(request.getAttribute(
                UserSessionAuthenticationInterceptor.BINDING_ATTEMPTED_ATTRIBUTE))
                .isEqualTo(Boolean.TRUE);
        assertThat(request.getAttribute(
                UserSessionAuthenticationInterceptor.BINDING_RESULT_ATTRIBUTE))
                .isEqualTo("preauth_required");
        verify(service, never()).bootstrap(any(SessionBootstrapCommand.class));
        verify(service, never()).bootstrap(
                any(SessionBootstrapCommand.class),
                any(PreAuthSessionBinding.class));
    }

    @Test
    void androidCannotUseTheBrowserBootstrapEndpoint() {
        assertThatThrownBy(() -> controller.bootstrap(
                new SessionController.SessionRequest("android-rt"),
                "device-1",
                "ANDROID",
                new MockHttpServletRequest(),
                new MockHttpServletResponse()))
                .isInstanceOf(WebInvalidInputException.class);

        verify(service, never()).bootstrap(any());
    }

    @Test
    void h5LogoutUsesTheRefreshCookieAndClearsBrowserCookiesAfterSuccess() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookieWriter.REFRESH_COOKIE, "browser-rt"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        controller.logout(
                new SessionController.SessionRequest("ignored-body-rt"),
                "device-1",
                "csrf-value",
                "H5",
                request,
                servletResponse);

        ArgumentCaptor<LogoutCommand> command = ArgumentCaptor.forClass(LogoutCommand.class);
        InOrder order = inOrder(service, preAuthService, cookieWriter, preAuthTransport);
        order.verify(service).logout(command.capture());
        order.verify(preAuthTransport).read(request, RiskScope.USER);
        order.verify(preAuthService).revoke(RiskScope.USER, "user-preauth");
        order.verify(cookieWriter).clearSession(servletResponse);
        order.verify(preAuthTransport).clearCookie(servletResponse, RiskScope.USER);
        assertThat(command.getValue().getRefreshToken()).isEqualTo("browser-rt");
    }

    @Test
    void h5LogoutKeepsCookiesForRecoverableCsrfBootstrap() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookieWriter.REFRESH_COOKIE, "browser-rt"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        doThrow(new SessionAuthenticationException(
                SessionAuthenticationErrorCode.CSRF_INVALID,
                "csrf mismatch",
                false))
                .when(service).logout(any());

        assertThatThrownBy(() -> controller.logout(
                null,
                "device-1",
                "csrf-value",
                "H5",
                request,
                servletResponse))
                .isInstanceOf(SessionAuthenticationException.class);

        verify(cookieWriter, never()).clearSession(servletResponse);
        verify(preAuthTransport, never()).clearCookie(servletResponse, RiskScope.USER);
    }

    @Test
    void androidLogoutUsesTheRequestBodyAndNeverWritesBrowserCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookieWriter.REFRESH_COOKIE, "ignored-browser-rt"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        controller.logout(
                new SessionController.SessionRequest("android-rt"),
                "device-1",
                "csrf-value",
                "ANDROID",
                request,
                servletResponse);

        ArgumentCaptor<LogoutCommand> command = ArgumentCaptor.forClass(LogoutCommand.class);
        verify(service).logout(command.capture());
        assertThat(command.getValue().getRefreshToken()).isEqualTo("android-rt");
        verify(cookieWriter, never()).clearSession(servletResponse);
        verify(preAuthTransport, never()).clearCookie(servletResponse, RiskScope.USER);
    }

    @Test
    void h5LogoutAllUsesAuthenticatedPrincipalAndClearsBrowserCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(AuthCookieWriter.ACCESS_COOKIE, "browser-at"),
                new Cookie(AuthCookieWriter.REFRESH_COOKIE, "browser-rt"));
        request.setAttribute(
                UserSessionAuthenticationInterceptor.PRINCIPAL_ATTRIBUTE,
                new SessionPrincipal(10001L, "AAAAAAAAAAE", "User"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        SessionController.LogoutResponse response = controller.logoutAll(
                "H5", request, servletResponse);

        assertThat(response.loggedOut()).isTrue();
        verify(service).logoutAllForUser(10001L);
        verify(cookieWriter).clearSession(servletResponse);
        verify(preAuthTransport).clearCookie(servletResponse, RiskScope.USER);
    }

    @Test
    void androidLogoutAllUsesAuthenticatedPrincipalWithoutWritingBrowserCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer android-at");
        request.setAttribute(
                UserSessionAuthenticationInterceptor.PRINCIPAL_ATTRIBUTE,
                new SessionPrincipal(10001L, "AAAAAAAAAAE", "User"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        SessionController.LogoutResponse response = controller.logoutAll(
                "ANDROID", request, servletResponse);

        assertThat(response.loggedOut()).isTrue();
        verify(service).logoutAllForUser(10001L);
        verify(cookieWriter, never()).clearSession(servletResponse);
        verify(preAuthTransport, never()).clearCookie(servletResponse, RiskScope.USER);
    }

    private static SessionAuthenticationResult result() {
        return new SessionAuthenticationResult(
                new SessionPrincipal(1L, "AAAAAAAAAAE", "User"),
                "new-access-value",
                "csrf-value",
                REFRESH_EXPIRES_AT);
    }
}
