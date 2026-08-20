package com.example.temperate.web.auth.oauth.nativegoogle;

import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;

/**
 * 表示 Android Google ID Token 验证成功后得到的可信身份和待 Redis 一次性消费的原始 nonce。
 */
public record VerifiedGoogleNativeIdentity(
        TrustedOAuthIdentity identity,
        String rawNonce) {
}
