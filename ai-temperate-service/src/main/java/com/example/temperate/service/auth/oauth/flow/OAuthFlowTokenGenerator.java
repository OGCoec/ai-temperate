package com.example.temperate.service.auth.oauth.flow;

/**
 * 定义 OAuth Flow、state、launch ticket、浏览器绑定、nonce 与 PKCE verifier 的安全随机生成能力。
 */
public interface OAuthFlowTokenGenerator {

    String newFlowToken();

    String newState();

    String newLaunchTicket();

    String newBrowserBinding();

    String newNonce();

    String newPkceVerifier();
}
