package com.example.temperate.web.auth.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.session.access.AccessSessionService;
import com.example.temperate.service.auth.session.access.dto.SessionAccessCommand;
import com.example.temperate.service.auth.session.access.dto.SessionAccessResult;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.user.membership.MembershipExpirationService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestAccessService;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.user.membership.payment.loadtest.MembershipPaymentLoadtestRequestPolicy;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 验证普通用户拦截器按平台隔离读取 AT/RT，并在原请求响应中传递自动续签结果。
 */
class UserSessionAuthenticationInterceptorTest {

    private static final String DEVICE_ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String CSRF_TOKEN =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final Instant REFRESH_EXPIRES_AT =
            Instant.parse("2026-08-04T15:00:00Z");

    private AccessSessionService service;
    private AuthCookieWriter cookieWriter;
    private PreAuthService preAuthService;
    private MembershipExpirationService membershipExpirationService;
    private UserSessionAuthenticationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        service = mock(AccessSessionService.class);
        cookieWriter = mock(AuthCookieWriter.class);
        preAuthService = mock(PreAuthService.class);
        membershipExpirationService = mock(MembershipExpirationService.class);
        interceptor = new UserSessionAuthenticationInterceptor(
                service,
                cookieWriter,
                preAuthService,
                mock(NetworkRiskProperties.class),
                membershipExpirationService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticationComponentIsAMvcInterceptorAndNeverAServletFilter() {
        assertThat(HandlerInterceptor.class.isAssignableFrom(
                UserSessionAuthenticationInterceptor.class)).isTrue();
        assertThat(Filter.class.isAssignableFrom(
                UserSessionAuthenticationInterceptor.class)).isFalse();
    }

    @Test
    void h5ReadsOnlyCookieCredentialsAndWritesRenewedAccessCookie() {
        MockHttpServletRequest request = request("H5");
        request.addHeader("Authorization", "Bearer ignored-android-at");
        request.addHeader("X-Refresh-Token", "ignored-android-rt");
        request.setCookies(
                new Cookie(AuthCookieWriter.ACCESS_COOKIE, "browser-at"),
                new Cookie(AuthCookieWriter.REFRESH_COOKIE, "browser-rt"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(service.authenticateOrRenew(any(SessionAccessCommand.class)))
                .thenReturn(result(true));

        interceptor.preHandle(request, response, new Object());

        ArgumentCaptor<SessionAccessCommand> command =
                ArgumentCaptor.forClass(SessionAccessCommand.class);
        verify(service).authenticateOrRenew(command.capture());
        verify(membershipExpirationService).expireIfDue(10001L);
        assertThat(command.getValue().accessToken()).isEqualTo("browser-at");
        assertThat(command.getValue().refreshToken()).isEqualTo("browser-rt");
        assertThat(command.getValue().presentedCsrfToken()).isEqualTo(CSRF_TOKEN);
        verify(cookieWriter).writeAccessToken(response, "renewed-access-token");
        assertThat(response.getHeader(UserSessionAuthenticationInterceptor.RENEWED_HEADER))
                .isEqualTo("true");
        assertThat(response.getHeader(UserSessionAuthenticationInterceptor.NEW_ACCESS_HEADER))
                .isNull();
    }

    @Test
    void membershipOfferReadDoesNotMutateLazyExpirationState() {
        MockHttpServletRequest request = request("H5");
        request.setMethod("GET");
        request.setRequestURI("/api/user/membership-plan-offers");
        request.setCookies(
                new Cookie(AuthCookieWriter.ACCESS_COOKIE, "browser-at"),
                new Cookie(AuthCookieWriter.REFRESH_COOKIE, "browser-rt"));
        when(service.authenticateOrRenew(any(SessionAccessCommand.class)))
                .thenReturn(result(false));

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verifyNoInteractions(membershipExpirationService);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo(result(false).principal());
    }

    @Test
    void androidReadsOnlyHeadersAndReturnsRenewedAccessHeader() {
        MockHttpServletRequest request = request("ANDROID");
        request.addHeader("Authorization", "Bearer android-at");
        request.addHeader("X-Refresh-Token", "android-rt");
        request.setCookies(
                new Cookie(AuthCookieWriter.ACCESS_COOKIE, "ignored-browser-at"),
                new Cookie(AuthCookieWriter.REFRESH_COOKIE, "ignored-browser-rt"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(service.authenticateOrRenew(any(SessionAccessCommand.class)))
                .thenReturn(result(true));

        interceptor.preHandle(request, response, new Object());

        ArgumentCaptor<SessionAccessCommand> command =
                ArgumentCaptor.forClass(SessionAccessCommand.class);
        verify(service).authenticateOrRenew(command.capture());
        assertThat(command.getValue().accessToken()).isEqualTo("android-at");
        assertThat(command.getValue().refreshToken()).isEqualTo("android-rt");
        assertThat(response.getHeader(UserSessionAuthenticationInterceptor.RENEWED_HEADER))
                .isEqualTo("true");
        assertThat(response.getHeader(UserSessionAuthenticationInterceptor.NEW_ACCESS_HEADER))
                .isEqualTo("renewed-access-token");
    }

    @Test
    void reusesTheAuthenticatedResultDuringAsyncRedispatchWithoutRenewingTwice() {
        MockHttpServletRequest request = request("ANDROID");
        request.addHeader("Authorization", "Bearer android-at");
        request.addHeader("X-Refresh-Token", "android-rt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(service.authenticateOrRenew(any(SessionAccessCommand.class)))
                .thenReturn(result(false));

        interceptor.preHandle(request, response, new Object());
        SecurityContextHolder.clearContext();
        interceptor.preHandle(request, response, new Object());

        verify(service, times(1)).authenticateOrRenew(any(SessionAccessCommand.class));
        verify(membershipExpirationService, times(1)).expireIfDue(10001L);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo(result(false).principal());
    }

    @Test
    void membershipExpirationFailureStopsTheRequestBeforeEstablishingSecurityContext() {
        MockHttpServletRequest request = request("ANDROID");
        request.addHeader("Authorization", "Bearer android-at");
        request.addHeader("X-Refresh-Token", "android-rt");
        when(service.authenticateOrRenew(any(SessionAccessCommand.class)))
                .thenReturn(result(false));
        when(membershipExpirationService.expireIfDue(10001L))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(
                            SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE);
                    assertThat(exception.clearCookies()).isFalse();
                });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingRefreshTokenKeepsTheRefreshRequiredErrorAheadOfPreAuthBinding() {
        MockHttpServletRequest request = request("ANDROID");
        request.addHeader("Authorization", "Bearer android-at");
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                new PreAuthAccess(null, null));
        SessionAuthenticationException expected = new SessionAuthenticationException(
                SessionAuthenticationErrorCode.REFRESH_TOKEN_REQUIRED,
                "Refresh token is required.",
                true);
        when(service.authenticateOrRenew(any(SessionAccessCommand.class)))
                .thenThrow(expected);

        assertThatThrownBy(() -> interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object()))
                .isSameAs(expected);

        verifyNoInteractions(preAuthService);
        verifyNoInteractions(membershipExpirationService);
    }

    @Test
    void exactLoadtestRouteUsesBearerAccessTokenWithoutRtDeviceOrCsrf() {
        MembershipPaymentLoadtestAccessService loadtestAccessService =
                mock(MembershipPaymentLoadtestAccessService.class);
        MembershipPaymentLoadtestRequestPolicy policy =
                new MembershipPaymentLoadtestRequestPolicy(
                        new MembershipPaymentLoadtestProperties(
                                true, java.util.List.of(73014701344296960L)));
        interceptor = new UserSessionAuthenticationInterceptor(
                service,
                cookieWriter,
                preAuthService,
                mock(NetworkRiskProperties.class),
                membershipExpirationService,
                policy,
                loadtestAccessService);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/user/membership-orders");
        request.addHeader("Authorization", "Bearer loadtest-at");
        SessionPrincipal principal =
                new SessionPrincipal(73014701344296960L, "AKMEmwYi80A", "压测用户");
        when(loadtestAccessService.authenticate("loadtest-at")).thenReturn(principal);

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo(principal);
        verify(membershipExpirationService).expireIfDue(73014701344296960L);
        verifyNoInteractions(service, preAuthService, cookieWriter);
    }

    private static MockHttpServletRequest request(String platform) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", platform);
        request.addHeader("X-Device-Installation-Id", DEVICE_ID);
        request.addHeader("X-CSRF-Token", CSRF_TOKEN);
        return request;
    }

    private static SessionAccessResult result(boolean renewed) {
        return new SessionAccessResult(
                new SessionPrincipal(10001L, "AAAAAAAAAAE", "User"),
                renewed,
                renewed ? "renewed-access-token" : null,
                REFRESH_EXPIRES_AT);
    }
}
