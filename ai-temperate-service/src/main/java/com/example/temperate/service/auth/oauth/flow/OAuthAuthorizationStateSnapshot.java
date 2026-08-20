package com.example.temperate.service.auth.oauth.flow;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;

/**
 * 表示一次性领取的浏览器 Authorization Code 交换上下文。
 *
 * <p>PKCE verifier 仅在本次服务端换码期间驻留内存；state 已在返回该对象前由 Lua 删除。</p>
 */
public record OAuthAuthorizationStateSnapshot(
        HmacIdentifier flowId,
        OAuthProvider provider,
        OAuthClientPlatform platform,
        String codeVerifier,
        HmacIdentifier nonceId,
        String redirectUri) {
}
