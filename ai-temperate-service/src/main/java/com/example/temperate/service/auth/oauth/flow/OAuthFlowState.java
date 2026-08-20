package com.example.temperate.service.auth.oauth.flow;

/**
 * 表示 OAuth 登录从 Provider 证明到正式会话签发之间的服务端状态机阶段。
 */
public enum OAuthFlowState {
    PROVIDER_PENDING,
    PHONE_REQUIRED,
    HUMAN_VERIFICATION_REQUIRED,
    CODE_READY,
    READY_TO_COMPLETE,
    TOTP_REQUIRED,
    AUTHENTICATED,
    FAILED,
    EXPIRED
}
