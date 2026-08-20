package com.example.temperate.service.auth.oauth.flow;

import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import java.time.Instant;

/**
 * 表示 Redis 中 OAuth 短时流程的当前安全状态快照，不包含任何原始 Token 或授权码。
 */
public record OAuthFlowSnapshot(
        OAuthProvider provider,
        OAuthClientPlatform platform,
        OAuthInteractionMode interactionMode,
        OAuthFlowState state,
        TrustedOAuthIdentity trustedIdentity,
        long existingIdentityId,
        boolean phoneRequired,
        String lockedPhone,
        boolean phoneVerified,
        Instant createdAt,
        Instant expiresAt,
        Instant absoluteExpiresAt) {
}
