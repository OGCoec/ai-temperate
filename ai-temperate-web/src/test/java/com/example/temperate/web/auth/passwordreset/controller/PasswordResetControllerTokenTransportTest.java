package com.example.temperate.web.auth.passwordreset.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.passwordreset.dto.ForgetTokenResult;
import com.example.temperate.service.auth.passwordreset.dto.PasswordResetStartResult;
import com.example.temperate.service.auth.passwordreset.service.PasswordResetService;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * 验证找回密码流程 token 在 H5 HttpOnly Cookie 与 Android Header/响应体之间的分流契约。
 *
 * <p>职责边界：本测试只固定 Controller 层的 token 暴露规则，不替代服务层对 resetFlowToken 和 forgetToken 的一次性校验。</p>
 */
class PasswordResetControllerTokenTransportTest {

    private static final Instant START_EXPIRES_AT = Instant.parse("2026-07-18T00:10:00Z");
    private static final Instant FORGET_EXPIRES_AT = Instant.parse("2026-07-18T00:05:00Z");

    private PasswordResetService service;
    private AuthFlowCookieWriter flowCookieWriter;
    private PasswordResetController controller;

    @BeforeEach
    void setUp() {
        service = mock(PasswordResetService.class);
        flowCookieWriter = mock(AuthFlowCookieWriter.class);
        controller = new PasswordResetController(service, flowCookieWriter);
        when(service.start(any())).thenReturn(new PasswordResetStartResult(
                "reset-flow-token",
                "challenge-handle",
                START_EXPIRES_AT));
        when(service.verifyCode(any(), any())).thenReturn(new ForgetTokenResult(
                "forget-token",
                FORGET_EXPIRES_AT));
        when(service.verifyTurnstile(any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void h5StartWritesResetFlowCookieAndOmitsTokenFromJson() throws Exception {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        PasswordResetController.PasswordResetStartResponse response = controller.start(
                startRequest(),
                "device-1",
                "H5",
                new MockHttpServletRequest(),
                servletResponse);

        verify(flowCookieWriter).writePasswordResetFlow(
                servletResponse,
                "reset-flow-token",
                START_EXPIRES_AT);
        assertThat(response.resetFlowToken()).isNull();
        assertThat(response.challengeHandle()).isEqualTo("challenge-handle");
        String json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .writeValueAsString(response);
        assertThat(json)
                .doesNotContain("resetFlowToken")
                .contains("challengeHandle");
    }

    @Test
    void androidStartReturnsResetFlowTokenWithoutWritingCookie() {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        PasswordResetController.PasswordResetStartResponse response = controller.start(
                startRequest(),
                "device-1",
                "ANDROID",
                new MockHttpServletRequest(),
                servletResponse);

        verify(flowCookieWriter, never()).writePasswordResetFlow(any(), any(), any());
        assertThat(response.resetFlowToken()).isEqualTo("reset-flow-token");
        assertThat(response.challengeHandle()).isEqualTo("challenge-handle");
    }

    @Test
    void h5VerifyWritesForgetTokenCookieAndOmitsTokenFromJson() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(flowCookieWriter.resetFlowToken(request)).thenReturn("reset-cookie-token");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        PasswordResetController.ForgetTokenResponse response = controller.verify(
                new PasswordResetController.VerifyRequest("123456"),
                null,
                "challenge-handle",
                "device-1",
                "H5",
                request,
                servletResponse);

        verify(flowCookieWriter).writeForgetToken(
                servletResponse,
                "forget-token",
                FORGET_EXPIRES_AT);
        assertThat(response.forgetToken()).isNull();
        String json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .writeValueAsString(response);
        assertThat(json).doesNotContain("forgetToken");
    }

    @Test
    void androidVerifyReturnsForgetTokenWithoutWritingCookie() {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        PasswordResetController.ForgetTokenResponse response = controller.verify(
                new PasswordResetController.VerifyRequest("123456"),
                "android-reset-token",
                "challenge-handle",
                "device-1",
                "ANDROID",
                new MockHttpServletRequest(),
                servletResponse);

        verify(flowCookieWriter, never()).writeForgetToken(any(), any(), any());
        assertThat(response.forgetToken()).isEqualTo("forget-token");
    }

    @Test
    void sendPassesWhatsappDeliveryMethodToPhoneResetFlow() {
        controller.send(
                new PasswordResetController.CodeSendRequest(
                        VerificationDeliveryMethod.WHATSAPP),
                "reset-flow-token",
                "challenge-handle",
                "device-1",
                "ANDROID",
                new MockHttpServletRequest());

        verify(service).sendCode(any(), eq(VerificationDeliveryMethod.WHATSAPP));
    }

    @Test
    void turnstileCompletesReactiveServiceBeforeReturningAcceptance() {
        PasswordResetController.AcceptedResponse response =
                controller.verifyTurnstile(
                                new PasswordResetController.TurnstileRequest(
                                        "turnstile-token"),
                                "reset-flow-token",
                                "challenge-handle",
                                "device-1",
                                "ANDROID",
                                new MockHttpServletRequest())
                        .toFuture()
                        .join();

        assertThat(response.accepted()).isTrue();
        verify(service).verifyTurnstile(
                any(), eq("turnstile-token"));
    }

    private static PasswordResetController.StartRequest startRequest() {
        return new PasswordResetController.StartRequest(
                VerificationChannel.EMAIL,
                "user@example.test",
                null,
                null);
    }
}
