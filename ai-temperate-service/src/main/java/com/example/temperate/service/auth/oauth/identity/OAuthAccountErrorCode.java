package com.example.temperate.service.auth.oauth.identity;

/**
 * 表示 OAuth 账号解析与最终绑定过程中可对外归一化处理的失败类型。
 */
public enum OAuthAccountErrorCode {
    INVALID_IDENTITY,
    ACCOUNT_CONFLICT,
    ACCOUNT_UNAVAILABLE,
    PHONE_REQUIRED,
    PHONE_UNAVAILABLE,
    PERSISTENCE_FAILED
}
