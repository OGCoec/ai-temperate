package com.example.temperate.web.auth.login.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证登录 Controller 对 H5 Cookie 与 Android Token 响应体的传输隔离测试。
 */
class LoginControllerTokenTransportTest {

    private static final Instant REFRESH_EXPIRES_AT =
            Instant.parse("2026-07-15T03:00:00Z");

    private LoginStrategyRegistry strategies;
    private LoginCodeFlowService codeFlowService;
    private AuthCookieWriter cookieWriter;
    private LoginController controller;

    @BeforeEach
    void setUp() {
        strategies = mock(LoginStrategyRegistry.class);
        codeFlowService = mock(LoginCodeFlowService.class);
        cookieWriter = mock(AuthCookieWriter.class);
        controller = new LoginController(
                strategies,
                codeFlowService,
                cookieWriter);
        when(strategies.login(eq(LoginStrategyType.PASSWORD), any()))
                .thenReturn(result());
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
