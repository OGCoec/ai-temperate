package com.example.temperate.web.auth.login.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.login.code.service.LoginCodeFlowService;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.strategy.LoginStrategyRegistry;
import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * 验证登录 Controller 对 H5 Cookie 与 Android Token 响应体的传输隔离测试。
 */
class LoginControllerTokenTransportTest {

    private static final Instant REFRESH_EXPIRES_AT =
            Instant.parse("2026-07-15T03:00:00Z");

    private LoginStrategyRegistry strategies;
    private LoginCodeFlowService codeFlowService;
    private AuthCookieWriter cookieWriter;
    private PreAuthService preAuthService;
    private PreAuthTransport preAuthTransport;
    private RiskRequestContextResolver riskContextResolver;
    private NetworkRiskProperties riskProperties;
    private LoginController controller;

    @BeforeEach
    void setUp() {
        strategies = mock(LoginStrategyRegistry.class);
        codeFlowService = mock(LoginCodeFlowService.class);
        cookieWriter = mock(AuthCookieWriter.class);
        preAuthService = mock(PreAuthService.class);
        preAuthTransport = mock(PreAuthTransport.class);
        riskContextResolver = mock(RiskRequestContextResolver.class);
        riskProperties = mock(NetworkRiskProperties.class);
        when(riskProperties.mode()).thenReturn(NetworkRiskMode.DISABLED);
        controller = new LoginController(
                strategies,
                codeFlowService,
                cookieWriter,
                preAuthService,
                preAuthTransport,
                riskContextResolver,
                riskProperties);
        when(strategies.login(eq(LoginStrategyType.PASSWORD), any()))
                .thenReturn(result());
        when(codeFlowService.verifyTurnstile(any(), any()))
                .thenReturn(Mono.empty());
    }

    @Test
    void h5WritesAllCredentialsAsCookiesAndOmitsThemFromJson() throws Exception {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        LoginController.LoginResponse response = controller.password(
                passwordRequest(),
                "device-1",
                "H5",
                new MockHttpServletRequest(),
                servletResponse);

        verify(cookieWriter).writeSession(
                servletResponse,
                "access-value",
                "refresh-value",
                "csrf-value",
                REFRESH_EXPIRES_AT);
        assertThat(response.accessToken()).isNull();
        assertThat(response.refreshToken()).isNull();
        assertThat(response.csrfToken()).isNull();
        String json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .writeValueAsString(response);
        assertThat(json)
                .doesNotContain("accessToken")
                .doesNotContain("refreshToken")
                .doesNotContain("csrfToken");
    }

    @Test
    void androidReturnsAllCredentialsWithoutWritingAuthenticationCookies() {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        LoginController.LoginResponse response = controller.password(
                passwordRequest(),
                "device-1",
                "ANDROID",
                new MockHttpServletRequest(),
                servletResponse);

        verify(cookieWriter, never()).writeSession(any(), any(), any(), any(), any());
        assertThat(response.accessToken()).isEqualTo("access-value");
        assertThat(response.refreshToken()).isEqualTo("refresh-value");
        assertThat(response.csrfToken()).isEqualTo("csrf-value");
    }

    @Test
    void enforceModeDoesNotWriteSessionCookieBeforePreAuthRotationSucceeds() {
        when(riskProperties.mode()).thenReturn(NetworkRiskMode.ENFORCE);
        when(riskContextResolver.resolve(any())).thenReturn(Optional.of(
                new TrustedNetworkObservation(
                        "203.0.113.10",
                        "US",
                        64500L,
                        new BigDecimal("41.8781"),
                        new BigDecimal("-87.6298"),
                        Instant.parse("2026-07-25T12:00:00Z"))));
        when(preAuthService.promoteAuthenticated(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("PreAuth rotation failed."));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                mock(PreAuthAccess.class));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.password(
                        passwordRequest(),
                        "device-1",
                        "H5",
                        request,
                        servletResponse))
                .isInstanceOf(IllegalStateException.class);

        verify(cookieWriter, never()).writeSession(any(), any(), any(), any(), any());
    }

    @Test
    void observeModeIgnoresUnverifiedStalePreAuthInsteadOfPromotingIt() {
        when(riskProperties.mode()).thenReturn(NetworkRiskMode.OBSERVE);
        when(preAuthTransport.read(any(), any())).thenReturn("stale-preauth-value");
        when(riskContextResolver.resolve(any())).thenReturn(Optional.of(
                new TrustedNetworkObservation(
                        "203.0.113.10",
                        "US",
                        64500L,
                        new BigDecimal("41.8781"),
                        new BigDecimal("-87.6298"),
                        Instant.parse("2026-07-25T12:00:00Z"))));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        controller.password(
                passwordRequest(),
                "device-1",
                "H5",
                new MockHttpServletRequest(),
                servletResponse);

        verify(preAuthService, never()).promoteAuthenticated(any(), any(), any(), any());
        verify(cookieWriter).writeSession(
                servletResponse,
                "access-value",
                "refresh-value",
                "csrf-value",
                REFRESH_EXPIRES_AT);
    }

    @Test
    void codeSendPassesWhatsappDeliveryMethodToPhoneFlowService() {
        controller.sendCode(
                new LoginController.CodeSendRequest(VerificationDeliveryMethod.WHATSAPP),
                "flow-token",
                "challenge-handle",
                "device-1",
                new MockHttpServletRequest());

        verify(codeFlowService).sendCode(
                any(), eq(VerificationDeliveryMethod.WHATSAPP));
    }

    @Test
    void codeTurnstileCompletesReactiveServiceBeforeReturningAcceptance() {
        LoginController.FlowAcceptedResponse response =
                controller.verifyCodeTurnstile(
                                new LoginController.TurnstileRequest(
                                        "turnstile-token"),
                                "flow-token",
                                "challenge-handle",
                                "device-1",
                                new MockHttpServletRequest())
                        .toFuture()
                        .join();

        assertThat(response.accepted()).isTrue();
        verify(codeFlowService).verifyTurnstile(
                any(), eq("turnstile-token"));
    }

    private static LoginController.PasswordLoginRequest passwordRequest() {
        return new LoginController.PasswordLoginRequest(
                "user@example.test", null, null, "test-password");
    }

    private static LoginResult result() {
        return new LoginResult(
                "AAAAAAAAAAE",
                "User",
                "access-value",
                "refresh-value",
                "csrf-value",
                REFRESH_EXPIRES_AT);
    }
}
