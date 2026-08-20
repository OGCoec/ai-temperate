package com.example.temperate.service.auth.oauth.flow;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 表示浏览器 OAuth state 与发起浏览器绑定经 HMAC 后的服务端访问材料。
 */
public record ProtectedOAuthAuthorizationState(
        HmacIdentifier stateId,
        HmacIdentifier browserBindingId) {
}
