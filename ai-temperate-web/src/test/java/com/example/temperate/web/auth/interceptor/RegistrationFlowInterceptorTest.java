package com.example.temperate.web.auth.interceptor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.registration.enums.RegistrationDiagnosticCode;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.service.lifecycle.RegistrationService;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证注册流程在进入 Controller 前失败时仍保留可关联的内部诊断阶段。
 */
class RegistrationFlowInterceptorTest {

    @Test
    void classifiesProtectedTurnstileFlowMismatchBeforeControllerInvocation() {
        RegistrationService service = mock(RegistrationService.class);
        AuthFlowCookieWriter cookieWriter = mock(AuthFlowCookieWriter.class);
        when(cookieWriter.registration(any())).thenReturn(
                new AuthFlowCookieWriter.RegistrationFlowCookies(
                        "register-token", "flow-csrf", "challenge-handle"));
        when(service.status(any())).thenThrow(new RegistrationException(
                RegistrationErrorCode.REGISTRATION_FLOW_FORBIDDEN,
                "flow mismatch"));
        RegistrationFlowInterceptor interceptor =
                new RegistrationFlowInterceptor(service, cookieWriter);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/auth/register/turnstile");
        request.addHeader("X-Client-Platform", "H5");
        request.addHeader("X-Device-Installation-Id", "device-1");

        assertThatThrownBy(() -> interceptor.preHandle(
                        request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(RegistrationException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.code())
                            .isEqualTo(RegistrationErrorCode.REGISTRATION_FLOW_FORBIDDEN);
                    org.assertj.core.api.Assertions.assertThat(exception.diagnosticCode())
                            .contains(RegistrationDiagnosticCode.FLOW_ACCESS_REJECTED);
                });
    }
}
