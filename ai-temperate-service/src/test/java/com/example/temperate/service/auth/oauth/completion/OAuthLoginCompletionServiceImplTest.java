package com.example.temperate.service.auth.oauth.completion;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.login.completion.LoginCompletionService;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.oauth.completion.impl.OAuthLoginCompletionServiceImpl;
import com.example.temperate.service.auth.oauth.domain.OAuthProofType;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.flow.OAuthClientPlatform;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowAccess;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowService;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowSnapshot;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowState;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowStore;
import com.example.temperate.service.auth.oauth.flow.OAuthInteractionMode;
import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthFlowAccess;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountFinalizationService;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneRiskService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 验证 OAuth 最终登录先声明一次性完成权，再执行数据库裁决和统一会话/TOTP 门禁。
 */
class OAuthLoginCompletionServiceImplTest {

    @Test
    void shouldClaimFinalizeIssueAndRecordAuthenticatedState() {
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        OAuthFlowService flowService = mock(OAuthFlowService.class);
        OAuthFlowStore flowStore = mock(OAuthFlowStore.class);
        OAuthAccountFinalizationService finalization =
                mock(OAuthAccountFinalizationService.class);
        LoginCompletionService loginCompletion = mock(LoginCompletionService.class);
        OAuthPhoneRiskService phoneRisk = mock(OAuthPhoneRiskService.class);
        OAuthFlowAccess access = new OAuthFlowAccess("raw-flow", "device", "203.0.113.8");
        ProtectedOAuthFlowAccess protectedAccess = new ProtectedOAuthFlowAccess(
                id("flow"), id("device"), id("global-device"), id("ip"));
        TrustedOAuthIdentity identity = new TrustedOAuthIdentity(
                OAuthProvider.GOOGLE,
                "subject",
                "member@example.com",
                true,
                OAuthProofType.GOOGLE_NATIVE_ID_TOKEN);
        OAuthFlowSnapshot snapshot = new OAuthFlowSnapshot(
                OAuthProvider.GOOGLE,
                OAuthClientPlatform.ANDROID,
                OAuthInteractionMode.GOOGLE_NATIVE,
                OAuthFlowState.READY_TO_COMPLETE,
                identity,
                12L,
                true,
                "+14155550123",
                true,
                now,
                now.plusSeconds(600),
                now.plusSeconds(1800));
        AuthenticationContext context = new AuthenticationContext(
                12L, null, 1L, AccountStatus.ACTIVE, "用户", "member@example.com",
                "+14155550123", false);
        LoginResult result = new LoginResult(
                "AAAAAAAAAAE", "用户", "access", "refresh", "csrf", now.plusSeconds(600));
        when(flowService.protect(access)).thenReturn(protectedAccess);
        when(flowService.getRequired(access)).thenReturn(snapshot);
        when(finalization.finalizeIdentity(identity, "+14155550123")).thenReturn(context);
        when(loginCompletion.complete(context, "device")).thenReturn(result);
        OAuthLoginCompletionService service = new OAuthLoginCompletionServiceImpl(
                flowService, flowStore, finalization, loginCompletion, phoneRisk, clock);

        LoginResult actual = service.complete(access);

        assertSame(result, actual);
        verify(flowStore).claimCompletion(protectedAccess, now);
        verify(flowStore).markCompletionResult(
                protectedAccess, OAuthFlowState.AUTHENTICATED, now);
    }

    private static HmacIdentifier id(String value) {
        return HmacIdentifier.fromProtectedValue("A".repeat(43));
    }
}
