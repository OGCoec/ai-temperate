package com.example.temperate.service.auth.login.completion.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.login.dto.result.LoginFlowStatus;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.session.LoginSessionIssuer;
import com.example.temperate.service.auth.totp.login.TotpLoginService;
import com.example.temperate.service.auth.totp.login.dto.TotpLoginChallengeResult;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 验证第一因子完成后只由统一边界决定签发会话或进入 TOTP 挑战。
 */
class LoginCompletionServiceImplTest {

    private static final String DEVICE_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void issuesSessionImmediatelyWhenTotpIsDisabled() {
        LoginSessionIssuer issuer = mock(LoginSessionIssuer.class);
        TotpLoginService totpLoginService = mock(TotpLoginService.class);
        LoginResult authenticated = authenticated();
        AuthenticationContext context = context(false);
        when(issuer.issue(context, DEVICE_ID)).thenReturn(authenticated);
        LoginCompletionServiceImpl service =
                new LoginCompletionServiceImpl(issuer, totpLoginService);

        LoginResult result = service.complete(context, DEVICE_ID);

        assertThat(result).isSameAs(authenticated);
        verify(totpLoginService, never()).start(10001L, DEVICE_ID);
    }

    @Test
    void createsChallengeWithoutIssuingTokensWhenTotpIsEnabled() {
        LoginSessionIssuer issuer = mock(LoginSessionIssuer.class);
        TotpLoginService totpLoginService = mock(TotpLoginService.class);
        AuthenticationContext context = context(true);
        Instant expiresAt = Instant.parse("2026-08-04T10:05:00Z");
        when(totpLoginService.start(10001L, DEVICE_ID))
                .thenReturn(new TotpLoginChallengeResult("flow-token", expiresAt, 5));
        LoginCompletionServiceImpl service =
                new LoginCompletionServiceImpl(issuer, totpLoginService);

        LoginResult result = service.complete(context, DEVICE_ID);

        assertThat(result.getStatus()).isEqualTo(LoginFlowStatus.TOTP_REQUIRED);
        assertThat(result.getTotpFlowToken()).isEqualTo("flow-token");
        assertThat(result.getTotpExpiresAt()).isEqualTo(expiresAt);
        assertThat(result.getAccessToken()).isNull();
        assertThat(result.getRefreshToken()).isNull();
        verify(issuer, never()).issue(context, DEVICE_ID);
    }

    private static AuthenticationContext context(boolean totpEnabled) {
        return new AuthenticationContext(
                10001L,
                "{bcrypt}hash",
                1L,
                AccountStatus.ACTIVE,
                "用户",
                "user@example.test",
                null,
                totpEnabled);
    }

    private static LoginResult authenticated() {
        return new LoginResult(
                "AAAAAAAAAAE",
                "用户",
                "access",
                "refresh",
                "csrf",
                Instant.parse("2026-08-04T13:00:00Z"));
    }
}
