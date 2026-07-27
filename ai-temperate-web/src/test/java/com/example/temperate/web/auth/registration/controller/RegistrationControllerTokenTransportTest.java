package com.example.temperate.web.auth.registration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.registration.dto.command.RegistrationSendCodeCommand;
import com.example.temperate.service.registration.dto.result.RegistrationStartResult;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.dto.result.VerificationDispatchResult;
import com.example.temperate.service.registration.enums.RegistrationStatus;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.service.lifecycle.RegistrationService;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * 验证注册流程 token 的平台传输分流，以及受保护联系方式的响应与禁缓存契约。
 *
 * <p>职责边界：本测试只覆盖 Controller 对短期流程材料和已验证联系方式的 HTTP 表达，
 * 不模拟注册状态机内部的 Redis 校验。</p>
 */
class RegistrationControllerTokenTransportTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-07-18T00:10:00Z");

    private RegistrationService service;
    private AuthFlowCookieWriter flowCookieWriter;
    private RegistrationController controller;

    @BeforeEach
    void setUp() {
        service = mock(RegistrationService.class);
        flowCookieWriter = mock(AuthFlowCookieWriter.class);
        controller = new RegistrationController(service, flowCookieWriter);
        when(service.start(any())).thenReturn(new RegistrationStartResult(
                "register-token",
                "register-csrf",
                "challenge-handle",
                EXPIRES_AT));
        when(service.sendCode(any())).thenReturn(new VerificationDispatchResult(
                VerificationChannel.SMS, EXPIRES_AT.minusSeconds(30)));
    }

    @Test
    void h5StartWritesFlowCookiesAndOmitsSensitiveTokensFromJson() throws Exception {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        RegistrationController.StartResponse response = controller.start(
                startRequest(),
                "device-1",
                "H5",
                new MockHttpServletRequest(),
                servletResponse);

        verify(flowCookieWriter).writeRegistration(
                servletResponse,
                "register-token",
                "register-csrf",
                "challenge-handle",
                EXPIRES_AT);
        assertThat(response.registerToken()).isNull();
        assertThat(response.flowCsrf()).isNull();
        assertThat(response.challengeHandle()).isEqualTo("challenge-handle");
        String json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .writeValueAsString(response);
        assertThat(json)
                .doesNotContain("registerToken")
                .doesNotContain("flowCsrf")
                .contains("challengeHandle");
    }

    @Test
    void androidStartKeepsResponseTokensAndDoesNotWriteBrowserCookies() {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        RegistrationController.StartResponse response = controller.start(
                startRequest(),
                "device-1",
                "ANDROID",
                new MockHttpServletRequest(),
                servletResponse);

        verify(flowCookieWriter, never()).writeRegistration(any(), any(), any(), any(), any());
        assertThat(response.registerToken()).isEqualTo("register-token");
        assertThat(response.flowCsrf()).isEqualTo("register-csrf");
        assertThat(response.challengeHandle()).isEqualTo("challenge-handle");
    }

    @Test
    void statusOmitsContactsBeforeHumanVerificationAndDisablesCaching() throws Exception {
        when(service.status(any())).thenReturn(statusResult(false));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        RegistrationController.RegistrationStatusResponse response = controller.status(
                "register-token",
                "register-csrf",
                "challenge-handle",
                "device-1",
                "ANDROID",
                new MockHttpServletRequest(),
                servletResponse);

        assertThat(response.email()).isNull();
        assertThat(response.phoneE164()).isNull();
        assertThat(servletResponse.getHeader("Cache-Control"))
                .contains("private")
                .contains("no-store");
        String json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .writeValueAsString(response);
        assertThat(json)
                .doesNotContain("\"email\":")
                .doesNotContain("\"phoneE164\":")
                .doesNotContain("user@example.test")
                .doesNotContain("+14155552671");
    }

    @Test
    void statusReturnsVerifiedContactsAndDisablesCaching() {
        when(service.status(any())).thenReturn(statusResult(true));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        RegistrationController.RegistrationStatusResponse response = controller.status(
                "register-token",
                "register-csrf",
                "challenge-handle",
                "device-1",
                "ANDROID",
                new MockHttpServletRequest(),
                servletResponse);

        assertVerifiedContactsAndNoStore(response, servletResponse);
    }

    @Test
    void turnstileReturnsVerifiedContactsAndDisablesCaching() {
        when(service.verifyTurnstile(any()))
                .thenReturn(Mono.just(statusResult(true)));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        RegistrationController.RegistrationStatusResponse response =
                controller.turnstile(
                                new RegistrationController.TurnstileRequest(
                                        "turnstile-token"),
                                "register-token",
                                "register-csrf",
                                "challenge-handle",
                                "device-1",
                                "ANDROID",
                                new MockHttpServletRequest(),
                                servletResponse)
                        .toFuture()
                        .join();

        assertVerifiedContactsAndNoStore(response, servletResponse);
    }

    @Test
    void phoneSendPassesWhatsappAsDeliveryMethodWithoutExposingProvider() {
        controller.sendPhone(
                new RegistrationController.PhoneCodeSendRequest(
                        VerificationDeliveryMethod.WHATSAPP),
                "register-token",
                "register-csrf",
                "challenge-handle",
                "device-1",
                "ANDROID",
                new MockHttpServletRequest());

        ArgumentCaptor<RegistrationSendCodeCommand> commandCaptor =
                ArgumentCaptor.forClass(RegistrationSendCodeCommand.class);
        verify(service).sendCode(commandCaptor.capture());
        assertThat(commandCaptor.getValue().channel()).isEqualTo(VerificationChannel.SMS);
        assertThat(commandCaptor.getValue().deliveryMethod())
                .isEqualTo(VerificationDeliveryMethod.WHATSAPP);
    }

    @Test
    void verifyCodesReturnsVerifiedContactsAndDisablesCaching() {
        when(service.verifyCodes(any())).thenReturn(statusResult(true));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        RegistrationController.RegistrationStatusResponse response = controller.verifyCodes(
                new RegistrationController.VerifyCodesRequest("123456", "654321"),
                "register-token",
                "register-csrf",
                "challenge-handle",
                "device-1",
                "ANDROID",
                new MockHttpServletRequest(),
                servletResponse);

        assertVerifiedContactsAndNoStore(response, servletResponse);
    }

    private static void assertVerifiedContactsAndNoStore(
            RegistrationController.RegistrationStatusResponse response,
            MockHttpServletResponse servletResponse) {
        assertThat(response.email()).isEqualTo("user@example.test");
        assertThat(response.phoneE164()).isEqualTo("+14155552671");
        assertThat(servletResponse.getHeader("Cache-Control"))
                .contains("private")
                .contains("no-store");
    }

    private static RegistrationStatusResult statusResult(boolean humanVerified) {
        return new RegistrationStatusResult(
                RegistrationStatus.ACTIVE,
                humanVerified,
                false,
                false,
                EXPIRES_AT.minusSeconds(60),
                EXPIRES_AT,
                EXPIRES_AT.plusSeconds(1200),
                "user@example.test",
                "+14155552671");
    }

    private static RegistrationController.StartRequest startRequest() {
        return new RegistrationController.StartRequest(
                "user@example.test",
                "US",
                "4155552671");
    }
}
