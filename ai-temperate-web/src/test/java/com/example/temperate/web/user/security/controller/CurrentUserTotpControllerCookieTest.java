package com.example.temperate.web.user.security.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.login.code.service.LoginCodeFlowService;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.totp.management.TotpManagementService;
import com.example.temperate.service.auth.totp.management.dto.TotpStateChangeResult;
import com.example.temperate.service.auth.totp.stepup.TotpStepUpService;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证 TOTP 安全状态修改成功并撤销全部 RT 后会清理当前 H5 的认证与 PreAuth Cookie。
 */
class CurrentUserTotpControllerCookieTest {

    private TotpManagementService managementService;
    private AuthCookieWriter cookieWriter;
    private PreAuthTransport preAuthTransport;
    private CurrentUserTotpController controller;

    @BeforeEach
    void setUp() {
        managementService = mock(TotpManagementService.class);
        cookieWriter = mock(AuthCookieWriter.class);
        preAuthTransport = mock(PreAuthTransport.class);
        controller = new CurrentUserTotpController(
                managementService,
                mock(TotpStepUpService.class),
                mock(LoginCodeFlowService.class),
                cookieWriter,
                preAuthTransport,
                mock(RiskRequestContextResolver.class));
    }

    @Test
    void confirmSetupClearsCurrentBrowserCredentialsAfterSuccess() {
        SessionPrincipal principal = new SessionPrincipal(10001L, "AAAAAAAAAAE", "User");
        when(managementService.confirmSetup(10001L, "device-1", "setup-token", "123456"))
                .thenReturn(new TotpStateChangeResult(true, true));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.confirmSetup(
                principal,
                "device-1",
                "H5",
                new CurrentUserTotpController.SetupConfirmRequest(
                        "setup-token", "123456"),
                response);

        verify(cookieWriter).clearSession(response);
        verify(preAuthTransport).clearCookie(response, RiskScope.USER);
    }

    @Test
    void disableClearsCurrentBrowserCredentialsAfterSuccess() {
        SessionPrincipal principal = new SessionPrincipal(10001L, "AAAAAAAAAAE", "User");
        when(managementService.disable(
                        10001L,
                        "device-1",
                        "step-up-token",
                        "654321"))
                .thenReturn(new TotpStateChangeResult(false, true));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.disable(
                principal,
                "device-1",
                "H5",
                new CurrentUserTotpController.DisableRequest(
                        "step-up-token", "654321"),
                response);

        verify(cookieWriter).clearSession(response);
        verify(preAuthTransport).clearCookie(response, RiskScope.USER);
    }
}
