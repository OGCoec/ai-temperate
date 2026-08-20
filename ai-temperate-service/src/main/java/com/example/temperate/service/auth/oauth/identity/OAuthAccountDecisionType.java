package com.example.temperate.service.auth.oauth.identity;

/**
 * 表示可信 OAuth 身份下一步可以直接完成登录，还是必须先证明手机号归属。
 */
public enum OAuthAccountDecisionType {
    AUTHENTICATE,
    PHONE_REQUIRED
}
