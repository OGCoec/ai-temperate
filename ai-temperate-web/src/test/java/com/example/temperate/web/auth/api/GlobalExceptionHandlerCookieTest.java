package com.example.temperate.web.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.phonecountry.service.exception.PhoneCountryTimeoutException;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.auth.passwordreset.PasswordResetErrorCode;
import com.example.temperate.service.auth.passwordreset.PasswordResetException;
import com.example.temperate.service.humanverification.HumanVerificationType;
import com.example.temperate.service.humanverification.exception.HumanVerificationUnavailableException;
import com.example.temperate.service.registration.enums.RegistrationDiagnosticCode;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.PreAuthTransport;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 验证终止性 H5 会话错误会按平台和错误类型清理正确 Cookie 的测试。
 */
class GlobalExceptionHandlerCookieTest {

    private AuthCookieWriter cookieWriter;
    private AuthFlowCookieWriter flowCookieWriter;
    private PreAuthTransport preAuthTransport;
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        cookieWriter = mock(AuthCookieWriter.class);
        flowCookieWriter = mock(AuthFlowCookieWriter.class);
        preAuthTransport = mock(PreAuthTransport.class);
        handler = new GlobalExceptionHandler(
                Clock.systemUTC(), cookieWriter, flowCookieWriter, preAuthTransport);
    }

    @Test
    void passwordPolicyErrorsUseStableBadRequestAndConflictStatuses() {
        var registrationResponse = handler.handleRegistration(
                new RegistrationException(
                        RegistrationErrorCode.PASSWORD_STRENGTH_INSUFFICIENT,
                        "internal message"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());
        var resetResponse = handler.handlePasswordReset(
                new PasswordResetException(
                        PasswordResetErrorCode.PASSWORD_STRENGTH_INSUFFICIENT,
                        "密码强度不足。"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());
        var loginResponse = handler.handleLogin(
                new LoginException(
                        LoginErrorCode.PASSWORD_RESET_REQUIRED,
                        "必须先重置密码。"));

        assertThat(registrationResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(registrationResponse.getBody()).isNotNull();
        assertThat(registrationResponse.getBody().code())
                .isEqualTo("PASSWORD_STRENGTH_INSUFFICIENT");
        assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resetResponse.getBody()).isNotNull();
        assertThat(resetResponse.getBody().code())
                .isEqualTo("PASSWORD_STRENGTH_INSUFFICIENT");
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(loginResponse.getBody()).isNotNull();
        assertThat(loginResponse.getBody().code()).isEqualTo("PASSWORD_RESET_REQUIRED");
    }

    @Test
    void h5TerminalSessionErrorsClearAllAuthenticationCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "H5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleSession(
                exception(SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID, true),
                request,
                response);

        verify(cookieWriter).clearSession(response);
        verify(preAuthTransport).clearCookie(response, RiskScope.USER);
    }

    @Test
    void h5MissingAccessTokenAlsoClearsTheUnusableRefreshSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "H5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleSession(
                exception(SessionAuthenticationErrorCode.ACCESS_TOKEN_REQUIRED, true),
                request,
                response);

        verify(cookieWriter).clearSession(response);
        verify(preAuthTransport).clearCookie(response, RiskScope.USER);
    }

    @Test
    void androidSessionErrorsNeverWriteBrowserCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "ANDROID");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleSession(
                exception(SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID, true),
                request,
                response);

        verify(cookieWriter, never()).clearSession(response);
        verify(preAuthTransport, never()).clearCookie(response, RiskScope.USER);
    }

    @Test
    void h5RegistrationFlowExpiryClearsRegistrationFlowCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "H5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleRegistration(
                new RegistrationException(
                        RegistrationErrorCode.REGISTRATION_FLOW_EXPIRED,
                        "expired"),
                request,
                response);

        verify(flowCookieWriter).clearRegistration(response);
    }

    @Test
    void turnstileDiagnosticReasonNeverEntersThePublicErrorBody() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "H5");

        var response = handler.handleRegistration(
                new RegistrationException(
                        RegistrationErrorCode.TURNSTILE_REJECTED,
                        "internal provider detail",
                        RegistrationDiagnosticCode.TOKEN_TIMEOUT_OR_DUPLICATE),
                request,
                new MockHttpServletResponse());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("TURNSTILE_REJECTED");
        assertThat(response.getBody().message()).isEqualTo("请先完成人机验证。");
        assertThat(response.getBody().toString())
                .doesNotContain("TOKEN_TIMEOUT_OR_DUPLICATE")
                .doesNotContain("internal provider detail");
    }

    @Test
    void h5PasswordResetCompletionFailureClearsResetFlowCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "H5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handlePasswordReset(
                new PasswordResetException(
                        PasswordResetErrorCode.SESSION_REVOCATION_FAILED,
                        "revocation failed"),
                request,
                response);

        verify(flowCookieWriter).clearPasswordReset(response);
    }

    @Test
    void androidFlowErrorsNeverWriteBrowserFlowCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "ANDROID");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleRegistration(
                new RegistrationException(
                        RegistrationErrorCode.REGISTRATION_FLOW_EXPIRED,
                        "expired"),
                request,
                response);
        handler.handlePasswordReset(
                new PasswordResetException(
                        PasswordResetErrorCode.FORGET_TOKEN_INVALID,
                        "invalid"),
                request,
                response);

        verify(flowCookieWriter, never()).clearRegistration(response);
        verify(flowCookieWriter, never()).clearPasswordReset(response);
    }

    @Test
    void csrfSessionErrorsUseTheStableUnauthorizedStatus() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "H5");

        var response = handler.handleSession(
                exception(SessionAuthenticationErrorCode.CSRF_INVALID, false),
                request,
                new MockHttpServletResponse());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void sessionInfrastructureErrorsUseServiceUnavailableStatus() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "H5");
        MockHttpServletResponse responseTarget = new MockHttpServletResponse();

        var response = handler.handleSession(
                exception(SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE, false),
                request,
                responseTarget);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(cookieWriter, never()).clearSession(responseTarget);
        verify(preAuthTransport, never()).clearCookie(responseTarget, RiskScope.USER);
    }

    @Test
    void missingStaticResourcesReturnNotFoundInsteadOfUnexpectedServerError() {
        var response = handler.handleResourceNotFound(
                new NoResourceFoundException(HttpMethod.GET, "favicon.ico"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getCacheControl()).contains("private", "no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void missingApiHandlerUsesTheSameNonCachedNotFoundEnvelope() {
        var response = handler.handleResourceNotFound(
                new NoHandlerFoundException(
                        "GET", "/api/not-exist", HttpHeaders.EMPTY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getCacheControl()).contains("private", "no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void knownRouteWithWrongMethodReturnsNonCachedMethodNotAllowedAndAllowHeader() {
        var exception = mock(HttpRequestMethodNotSupportedException.class);
        when(exception.getSupportedHttpMethods())
                .thenReturn(java.util.Set.of(HttpMethod.GET, HttpMethod.HEAD));

        var response = handler.handleMethodNotAllowed(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow())
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.HEAD);
        assertThat(response.getHeaders().getCacheControl()).contains("private", "no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void phoneCountryTimeoutUsesTheStableNonCachedTooManyRequestsResponse() {
        var response = handler.handlePhoneCountryTimeout(new PhoneCountryTimeoutException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getCacheControl()).contains("private", "no-store");
        assertThat(response.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("PHONE_COUNTRY_TIMEOUT");
        assertThat(response.getBody().message()).isEqualTo("国家或地区识别超时，请手动选择。");
    }

    @Test
    void humanVerificationUnavailableDoesNotClearAuthenticationOrFlowCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "H5");

        handler.handleHumanVerificationUnavailable(
                new HumanVerificationUnavailableException(
                        HumanVerificationType.TURNSTILE,
                        new IllegalStateException("simulated transport failure")),
                request);

        verifyNoInteractions(cookieWriter, flowCookieWriter, preAuthTransport);
    }

    private static SessionAuthenticationException exception(
            SessionAuthenticationErrorCode code, boolean clearCookies) {
        return new SessionAuthenticationException(code, "session failure", clearCookies);
    }
}
