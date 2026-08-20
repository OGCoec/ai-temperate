package com.example.temperate.service.auth.oauth.flow;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 表示构造 Provider 授权 URL 所需的一次性 state、PKCE 和 Google nonce 材料。
 */
public record OAuthBrowserAuthorization(
        String rawState,
        String codeChallenge,
        String rawNonce,
        HmacIdentifier flowId) {
}
