package com.example.temperate.service.auth.oauth.flow;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;

/**
 * 定义 OAuth Flow 启动、浏览器授权上下文创建、状态查询和一次性 nonce 消费能力。
 */
public interface OAuthFlowService {

    OAuthFlowStartResult start(OAuthFlowStartCommand command);

    ProtectedOAuthFlowAccess protect(OAuthFlowAccess access);

    OAuthFlowSnapshot getRequired(OAuthFlowAccess access);

    OAuthBrowserAuthorization beginBrowserAuthorization(
            HmacIdentifier flowId,
            OAuthProvider provider,
            OAuthClientPlatform platform,
            String rawBrowserBinding,
            String redirectUri);

    HmacIdentifier protectFlowToken(String rawFlowToken);

    OAuthAuthorizationStateSnapshot consumeBrowserAuthorization(
            String rawState,
            String rawBrowserBinding,
            OAuthProvider provider);

    HmacIdentifier consumeLaunchTicket(String rawLaunchTicket, OAuthProvider provider);

    void consumeNativeNonce(OAuthFlowAccess access, String rawNonce);

    String newBrowserBinding();
}
