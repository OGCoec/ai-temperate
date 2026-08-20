package com.example.temperate.service.auth.oauth.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.service.auth.oauth.domain.OAuthProofType;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowStore;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountDecision;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountDecisionType;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountResolutionService;
import com.example.temperate.service.auth.oauth.identity.OAuthSubjectBindingRegistry;
import com.example.temperate.service.auth.oauth.identity.OAuthSubjectBindingStrategy;
import com.example.temperate.service.auth.oauth.identity.impl.OAuthAccountResolutionServiceImpl;
import com.example.temperate.service.auth.oauth.identity.impl.OAuthProviderCompletionServiceImpl;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证真实 OAuth 账号解析服务在每个外部调用边界前写入固定诊断阶段，避免切面日志把故障归到错误步骤。
 */
class OAuthAccountResolutionStageContractTest {

    private UserLoginIdentityMapper mapper;
    private OAuthSubjectBindingStrategy strategy;
    private OAuthAccountResolutionService resolutionService;

    @BeforeEach
    void setUp() {
        mapper = mock(UserLoginIdentityMapper.class);
        strategy = mock(OAuthSubjectBindingStrategy.class);
        when(strategy.provider()).thenReturn(OAuthProvider.GITHUB);
        resolutionService = new OAuthAccountResolutionServiceImpl(
                mapper,
                new RegistrationInputNormalizer(),
                new OAuthSubjectBindingRegistry(Map.of("github", strategy)));
    }

    @Test
    void marksSubjectLookupBeforeProviderStrategyCall() {
        IllegalStateException expected = new IllegalStateException("sentinel");
        when(strategy.findBySubject(anyString())).thenThrow(expected);

        try (OAuthAccountResolutionDiagnosticContext.Scope ignored =
                OAuthAccountResolutionDiagnosticContext.open()) {
            IllegalStateException actual = assertThrows(
                    IllegalStateException.class,
                    () -> resolutionService.resolve(identity()));

            assertSame(expected, actual);
            assertEquals(
                    OAuthAccountResolutionDiagnosticContext.Stage.SUBJECT_LOOKUP,
                    OAuthAccountResolutionDiagnosticContext.currentStage());
        }
    }

    @Test
    void marksEmailLookupBeforeNormalizedEmailQuery() {
        IllegalStateException expected = new IllegalStateException("sentinel");
        when(strategy.findBySubject(anyString())).thenReturn(null);
        when(mapper.findByNormalizedEmail("member@example.com")).thenThrow(expected);

        try (OAuthAccountResolutionDiagnosticContext.Scope ignored =
                OAuthAccountResolutionDiagnosticContext.open()) {
            IllegalStateException actual = assertThrows(
                    IllegalStateException.class,
                    () -> resolutionService.resolve(identity()));

            assertSame(expected, actual);
            assertEquals(
                    OAuthAccountResolutionDiagnosticContext.Stage.EMAIL_LOOKUP,
                    OAuthAccountResolutionDiagnosticContext.currentStage());
        }
    }

    @Test
    void marksAuthenticationContextLookupBeforeAccountStateQuery() {
        IllegalStateException expected = new IllegalStateException("sentinel");
        UserLoginIdentity emailMatch = new UserLoginIdentity();
        emailMatch.setId(41L);
        when(strategy.findBySubject(anyString())).thenReturn(null);
        when(mapper.findByNormalizedEmail("member@example.com")).thenReturn(emailMatch);
        when(strategy.subjectOf(emailMatch)).thenReturn(null);
        when(mapper.findAuthenticationById(41L)).thenThrow(expected);

        try (OAuthAccountResolutionDiagnosticContext.Scope ignored =
                OAuthAccountResolutionDiagnosticContext.open()) {
            IllegalStateException actual = assertThrows(
                    IllegalStateException.class,
                    () -> resolutionService.resolve(identity()));

            assertSame(expected, actual);
            assertEquals(
                    OAuthAccountResolutionDiagnosticContext.Stage.AUTH_CONTEXT_LOOKUP,
                    OAuthAccountResolutionDiagnosticContext.currentStage());
        }
    }

    @Test
    void marksFlowPersistenceBeforeRedisStateTransition() {
        OAuthAccountResolutionService resolved = mock(OAuthAccountResolutionService.class);
        OAuthFlowStore flowStore = mock(OAuthFlowStore.class);
        OAuthAccountDecision decision = new OAuthAccountDecision(
                OAuthAccountDecisionType.AUTHENTICATE,
                OAuthProvider.GITHUB,
                41L,
                false);
        IllegalStateException expected = new IllegalStateException("sentinel");
        HmacIdentifier flowId = HmacIdentifier.fromProtectedValue("A".repeat(43));
        when(resolved.resolve(identity())).thenReturn(decision);
        org.mockito.Mockito.doThrow(expected)
                .when(flowStore)
                .completeProvider(
                        org.mockito.ArgumentMatchers.eq(flowId),
                        org.mockito.ArgumentMatchers.any(TrustedOAuthIdentity.class),
                        org.mockito.ArgumentMatchers.eq(decision),
                        org.mockito.ArgumentMatchers.any());
        OAuthProviderCompletionServiceImpl completionService =
                new OAuthProviderCompletionServiceImpl(resolved, flowStore, Clock.systemUTC());

        try (OAuthAccountResolutionDiagnosticContext.Scope ignored =
                OAuthAccountResolutionDiagnosticContext.open()) {
            IllegalStateException actual = assertThrows(
                    IllegalStateException.class,
                    () -> completionService.accept(flowId, identity()));

            assertSame(expected, actual);
            assertEquals(
                    OAuthAccountResolutionDiagnosticContext.Stage.FLOW_PERSISTENCE,
                    OAuthAccountResolutionDiagnosticContext.currentStage());
        }
    }

    private static TrustedOAuthIdentity identity() {
        return new TrustedOAuthIdentity(
                OAuthProvider.GITHUB,
                "9001",
                "member@example.com",
                true,
                OAuthProofType.BROWSER_AUTHORIZATION_CODE);
    }
}
