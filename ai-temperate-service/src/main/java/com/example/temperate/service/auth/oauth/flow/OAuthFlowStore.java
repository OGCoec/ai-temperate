package com.example.temperate.service.auth.oauth.flow;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountDecision;
import java.time.Instant;

/**
 * 定义 OAuth Flow、浏览器 state、Android launch ticket 与原生 nonce 的短时 Redis 状态合同。
 */
public interface OAuthFlowStore {

    void create(
            ProtectedOAuthFlowAccess access,
            OAuthProvider provider,
            OAuthClientPlatform platform,
            OAuthInteractionMode interactionMode,
            HmacIdentifier nativeNonceId,
            Instant createdAt);

    OAuthFlowSnapshot getRequired(ProtectedOAuthFlowAccess access, Instant now);

    void completeProvider(
            HmacIdentifier flowId,
            TrustedOAuthIdentity identity,
            OAuthAccountDecision decision,
            Instant now);

    void createAuthorizationState(
            ProtectedOAuthAuthorizationState state,
            HmacIdentifier flowId,
            OAuthProvider provider,
            OAuthClientPlatform platform,
            String codeVerifier,
            HmacIdentifier nonceId,
            String redirectUri,
            Instant createdAt);

    OAuthAuthorizationStateSnapshot consumeAuthorizationState(
            ProtectedOAuthAuthorizationState state,
            OAuthProvider expectedProvider,
            Instant now);

    void createLaunchTicket(
            HmacIdentifier launchTicketId,
            HmacIdentifier flowId,
            OAuthProvider provider,
            Instant createdAt);

    HmacIdentifier consumeLaunchTicket(
            HmacIdentifier launchTicketId,
            OAuthProvider expectedProvider,
            Instant now);

    void consumeNativeNonce(
            ProtectedOAuthFlowAccess access,
            HmacIdentifier presentedNonceId,
            Instant now);

    void bindPhoneFlow(
            ProtectedOAuthFlowAccess access,
            HmacIdentifier phoneFlowId,
            HmacIdentifier phoneChallengeId,
            String normalizedPhone,
            Instant now);

    void markPhoneHumanVerified(
            ProtectedOAuthFlowAccess access,
            HmacIdentifier phoneFlowId,
            HmacIdentifier phoneChallengeId,
            Instant now);

    void requirePhoneCodeReady(
            ProtectedOAuthFlowAccess access,
            HmacIdentifier phoneFlowId,
            HmacIdentifier phoneChallengeId,
            Instant now);

    void markPhoneVerified(
            ProtectedOAuthFlowAccess access,
            HmacIdentifier phoneFlowId,
            HmacIdentifier phoneChallengeId,
            String verifiedPhone,
            Instant now);

    void claimCompletion(ProtectedOAuthFlowAccess access, Instant now);

    void releaseCompletionClaim(ProtectedOAuthFlowAccess access);

    void markCompletionResult(
            ProtectedOAuthFlowAccess access,
            OAuthFlowState resultState,
            Instant now);

    void markFailed(HmacIdentifier flowId, Instant now);

    void delete(HmacIdentifier flowId);
}
