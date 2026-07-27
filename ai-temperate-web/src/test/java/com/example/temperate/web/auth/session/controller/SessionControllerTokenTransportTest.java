package com.example.temperate.web.auth.session.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.dto.command.LogoutCommand;
import com.example.temperate.service.auth.session.authentication.dto.command.SessionAuthenticationCommand;
import com.example.temperate.service.auth.session.authentication.dto.command.SessionBootstrapCommand;
import com.example.temperate.service.auth.session.authentication.dto.result.SessionAuthenticationResult;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.auth.interceptor.AccessTokenAuthenticationInterceptor;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.PreAuthTransport;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证 H5/Android 刷新、恢复和退出会话的 Token 来源与响应隔离测试。
 */
class SessionControllerTokenTransportTest {

    private static final Instant REFRESH_EXPIRES_AT =
            Instant.parse("2026-07-15T03:00:00Z");

    private SessionAuthenticationService service;
    private AuthCookieWriter cookieWriter;
    private SessionController controller;

    @BeforeEach
    void setUp() {
        service = mock(SessionAuthenticationService.class);
        cookieWriter = mock(AuthCookieWriter.class);
        controller = new SessionController(
                service,
                cookieWriter,
                mock(PreAuthService.class),
                mock(PreAuthTransport.class),
                mock(NetworkRiskProperties.class));
        when(service.authenticate(any())).thenReturn(result());
        when(service.bootstrap(any())).thenReturn(result());
    }

    @Test
    void h5RefreshUsesOnlyCookiesAndOmitsTokensFromTheResponse() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(AuthCookieWriter.ACCESS_COOKIE, "browser-at"),
                new Cookie(AuthCookieWriter.REFRESH_COOKIE, "browser-rt"));
        request.addHeader("Authorization", "Bearer ignored-android-at");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        SessionController.SessionResponse response = controller.refresh(
                null,
                "device-1",
                "H5",
                "csrf-value",
                request,
                servletResponse);

        ArgumentCaptor<SessionAuthenticationCommand> command =
                ArgumentCaptor.forClass(SessionAuthenticationCommand.class);
        verify(service).authenticate(command.capture());
        assertThat(command.getValue().getAccessToken()).isEqualTo("browser-at");
        assertThat(command.getValue().getRefreshToken()).isEqualTo("browser-rt");
        verify(cookieWriter).writeSession(
                servletResponse,
                "new-access-value",
                "browser-rt",
                "csrf-value",
                REFRESH_EXPIRES_AT);
        assertThat(response.accessToken()).isNull();
        assertThat(response.csrfToken()).isNull();
    }

    @Test
    void androidRefreshUsesAuthorizationAndTheRequestBodyWithoutCookieFallback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(AuthCookieWriter.ACCESS_COOKIE, "ignored-browser-at"),
                new Cookie(AuthCookieWriter.REFRESH_COOKIE, "ignored-browser-rt"));
        request.addHeader("Authorization", "Bearer android-at");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        SessionController.SessionResponse response = controller.refresh(
                new SessionController.SessionRequest("android-rt"),
                "device-1",
                "ANDROID",
                "csrf-value",
                request,
                servletResponse);

        ArgumentCaptor<SessionAuthenticationCommand> command =
                ArgumentCaptor.forClass(SessionAuthenticationCommand.class);
        verify(service).authenticate(command.capture());
        assertThat(command.getValue().getAccessToken()).isEqualTo("android-at");
        assertThat(command.getValue().getRefreshToken()).isEqualTo("android-rt");
        verify(cookieWriter, never()).writeSession(any(), any(), any(), any(), any());
        assertThat(response.accessToken()).isEqualTo("new-access-value");
        assertThat(response.csrfToken()).isEqualTo("csrf-value");
    }

    @Test
    void h5RefreshDoesNotFallBackToAndroidHeaderOrBodyCredentials() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer ignored-android-at");

        controller.refresh(
                new SessionController.SessionRequest("ignored-android-rt"),
                "device-1",
                "H5",
                "csrf-value",
                request,
                new MockHttpServletResponse());

        ArgumentCaptor<SessionAuthenticationCommand> command =
                ArgumentCaptor.forClass(SessionAuthenticationCommand.class);
        verify(service).authenticate(command.capture());
        assertThat(command.getValue().getAccessToken()).isNull();
        assertThat(command.getValue().getRefreshToken()).isNull();
    }

    @Test
    void androidRefreshDoesNotFallBackToBrowserCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(AuthCookieWriter.ACCESS_COOKIE, "ignored-browser-at"),
                new Cookie(AuthCookieWriter.REFRESH_COOKIE, "ignored-browser-rt"));

        controller.refresh(
                null,
                "device-1",
                "ANDROID",
                "csrf-value",
                request,
                new MockHttpServletResponse());

        ArgumentCaptor<SessionAuthenticationCommand> command =
                ArgumentCaptor.forClass(SessionAuthenticationCommand.class);
        verify(service).authenticate(command.capture());
        assertThat(command.getValue().getAccessToken()).isNull();
        assertThat(command.getValue().getRefreshToken()).isNull();
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
                "csrf-value",
                REFRESH_EXPIRES_AT);
        assertThat(response.accessToken()).isNull();
        assertThat(response.csrfToken()).isNull();
    }

    @Test
    void androidCannotUseTheBrowserBootstrapEndpoint() {
        assertThatThrownBy(() -> controller.bootstrap(
                new SessionController.SessionRequest("android-rt"),
                "device-1",
                "ANDROID",
                new MockHttpServletRequest(),
                new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class);

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
        verify(service).logout(command.capture());
        assertThat(command.getValue().getRefreshToken()).isEqualTo("browser-rt");
        verify(cookieWriter).clearSession(servletResponse);
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
    }

    @Test
    void h5LogoutAllUsesAuthenticatedPrincipalAndClearsBrowserCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(AuthCookieWriter.ACCESS_COOKIE, "browser-at"),
                new Cookie(AuthCookieWriter.REFRESH_COOKIE, "browser-rt"));
        request.setAttribute(
                AccessTokenAuthenticationInterceptor.PRINCIPAL_ATTRIBUTE,
                new SessionPrincipal(10001L, "AAAAAAAAAAE", "User"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        SessionController.LogoutResponse response = controller.logoutAll(
                "H5", request, servletResponse);

        assertThat(response.loggedOut()).isTrue();
        verify(service).logoutAllForUser(10001L);
        verify(cookieWriter).clearSession(servletResponse);
    }

    @Test
    void androidLogoutAllUsesAuthenticatedPrincipalWithoutWritingBrowserCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer android-at");
        request.setAttribute(
                AccessTokenAuthenticationInterceptor.PRINCIPAL_ATTRIBUTE,
                new SessionPrincipal(10001L, "AAAAAAAAAAE", "User"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        SessionController.LogoutResponse response = controller.logoutAll(
                "ANDROID", request, servletResponse);

        assertThat(response.loggedOut()).isTrue();
        verify(service).logoutAllForUser(10001L);
        verify(cookieWriter, never()).clearSession(servletResponse);
    }

    private static SessionAuthenticationResult result() {
        return new SessionAuthenticationResult(
                new SessionPrincipal(1L, "AAAAAAAAAAE", "User"),
                "new-access-value",
                "csrf-value",
                REFRESH_EXPIRES_AT);
    }
}
