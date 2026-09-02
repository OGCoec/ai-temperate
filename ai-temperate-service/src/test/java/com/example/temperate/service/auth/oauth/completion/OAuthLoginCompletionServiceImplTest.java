package com.example.temperate.service.auth.oauth.completion;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.login.completion.LoginCompletionService;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.oauth.completion.OAuthLoginCompletionService;
import com.example.temperate.service.auth.oauth.completion.impl.OAuthLoginCompletionServiceImpl;
import com.example.temperate.service.auth.oauth.completion.observability.OAuthCompletionMetrics;
import com.example.temperate.service.auth.oauth.domain.OAuthProofType;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.flow.OAuthClientPlatform;
import com.example.temperate.service.auth.oauth.flow.OAuthCompletionClaim;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowAccess;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowErrorCode;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowException;
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
        CompletionFixture fixture = fixture(OAuthCompletionClaim.CLAIMED);

        LoginResult actual = fixture.service.complete(fixture.access);

        assertSame(fixture.result, actual);
        verify(fixture.flowStore).claimCompletion(fixture.protectedAccess, fixture.now);
        verify(fixture.flowStore).markCompletionResult(
                fixture.protectedAccess, OAuthFlowState.AUTHENTICATED, fixture.now);
    }

    @Test
    void shouldRejectConcurrentCompletionWithoutIssuingAnotherSession() {
        CompletionFixture fixture = fixture(OAuthCompletionClaim.IN_PROGRESS);

        OAuthFlowException exception = assertThrows(
                OAuthFlowException.class,
                () -> fixture.service.complete(fixture.access));

        assertSame(OAuthFlowErrorCode.COMPLETION_IN_PROGRESS, exception.code());
        verify(fixture.metrics).completionInProgress();
        verifyNoInteractions(fixture.finalization, fixture.loginCompletion);
    }

    @Test
    void shouldTreatAlreadyCompletedFlowAsAStableConflict() {
        CompletionFixture fixture = fixture(OAuthCompletionClaim.ALREADY_COMPLETED);

        OAuthFlowException exception = assertThrows(
                OAuthFlowException.class,
                () -> fixture.service.complete(fixture.access));

        assertSame(OAuthFlowErrorCode.ALREADY_COMPLETED, exception.code());
        verify(fixture.metrics).alreadyCompleted();
        verifyNoInteractions(fixture.finalization, fixture.loginCompletion);
    }

    @Test
    void shouldReleaseClaimWhenDatabaseFinalizationFails() {
        CompletionFixture fixture = fixture(OAuthCompletionClaim.CLAIMED);
        RuntimeException failure = new RuntimeException("database failed");
        when(fixture.finalization.finalizeIdentity(
                fixture.identity, "+14155550123")).thenThrow(failure);

        RuntimeException actual = assertThrows(
                RuntimeException.class,
                () -> fixture.service.complete(fixture.access));

        assertSame(failure, actual);
        verify(fixture.flowStore).releaseCompletionClaim(fixture.protectedAccess);
        verifyNoInteractions(fixture.loginCompletion);
    }

    @Test
    void shouldReleaseClaimWhenSessionIssuanceFails() {
        CompletionFixture fixture = fixture(OAuthCompletionClaim.CLAIMED);
        RuntimeException failure = new RuntimeException("session issuance failed");
        when(fixture.loginCompletion.complete(
                fixture.context, "device")).thenThrow(failure);

        RuntimeException actual = assertThrows(
                RuntimeException.class,
                () -> fixture.service.complete(fixture.access));

        assertSame(failure, actual);
        verify(fixture.flowStore).releaseCompletionClaim(fixture.protectedAccess);
    }

    @Test
    void shouldNeverReleaseClaimAfterSuccessfulSessionIssuance() {
        CompletionFixture fixture = fixture(OAuthCompletionClaim.CLAIMED);

        assertSame(fixture.result, fixture.service.complete(fixture.access));

        verify(fixture.flowStore, never()).releaseCompletionClaim(fixture.protectedAccess);
    }

    private static CompletionFixture fixture(OAuthCompletionClaim claim) {
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        OAuthFlowService flowService = mock(OAuthFlowService.class);
        OAuthFlowStore flowStore = mock(OAuthFlowStore.class);
        OAuthAccountFinalizationService finalization = mock(OAuthAccountFinalizationService.class);
        LoginCompletionService loginCompletion = mock(LoginCompletionService.class);
        OAuthPhoneRiskService phoneRisk = mock(OAuthPhoneRiskService.class);
        OAuthCompletionMetrics metrics = mock(OAuthCompletionMetrics.class);
        OAuthFlowAccess access = new OAuthFlowAccess("raw-flow", "device", "203.0.113.8");
        ProtectedOAuthFlowAccess protectedAccess = new ProtectedOAuthFlowAccess(
                id("flow"), id("device"), id("global-device"), id("ip"));
        TrustedOAuthIdentity identity = new TrustedOAuthIdentity(
                OAuthProvider.GOOGLE, "subject", "member@example.com", true,
                OAuthProofType.GOOGLE_NATIVE_ID_TOKEN);
        OAuthFlowSnapshot snapshot = new OAuthFlowSnapshot(
                OAuthProvider.GOOGLE, OAuthClientPlatform.ANDROID,
                OAuthInteractionMode.GOOGLE_NATIVE, OAuthFlowState.READY_TO_COMPLETE,
                identity, 12L, true, "+14155550123", true, now,
                now.plusSeconds(600), now.plusSeconds(1800));
        AuthenticationContext context = new AuthenticationContext(
                12L, null, 1L, AccountStatus.ACTIVE, "用户", "member@example.com",
                "+14155550123", false);
        LoginResult result = new LoginResult(
                "AAAAAAAAAAE", "用户", "access", "refresh", "csrf", now.plusSeconds(600));
        when(flowService.protect(access)).thenReturn(protectedAccess);
        when(flowService.getRequired(access)).thenReturn(snapshot);
        when(flowStore.claimCompletion(protectedAccess, now)).thenReturn(claim);
        when(finalization.finalizeIdentity(identity, "+14155550123")).thenReturn(context);
        when(loginCompletion.complete(context, "device")).thenReturn(result);
        OAuthLoginCompletionService service = new OAuthLoginCompletionServiceImpl(
                flowService, flowStore, finalization, loginCompletion, phoneRisk, metrics, clock);
        return new CompletionFixture(
                service, flowStore, finalization, loginCompletion, access,
                protectedAccess, identity, context, result, metrics, now);
    }

    private record CompletionFixture(
            OAuthLoginCompletionService service,
            OAuthFlowStore flowStore,
            OAuthAccountFinalizationService finalization,
            LoginCompletionService loginCompletion,
            OAuthFlowAccess access,
            ProtectedOAuthFlowAccess protectedAccess,
            TrustedOAuthIdentity identity,
            AuthenticationContext context,
            LoginResult result,
            OAuthCompletionMetrics metrics,
            Instant now) {
    }

    private static HmacIdentifier id(String value) {
        return HmacIdentifier.fromProtectedValue("A".repeat(43));
    }
}
