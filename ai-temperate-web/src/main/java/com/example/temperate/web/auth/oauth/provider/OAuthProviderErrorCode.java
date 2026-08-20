package com.example.temperate.web.auth.oauth.provider;

/**
 * 表示第三方授权拒绝、换码失败或身份声明不可信的稳定错误类型。
 */
public enum OAuthProviderErrorCode {
    AUTHORIZATION_REJECTED,
    TOKEN_EXCHANGE_FAILED,
    PROVIDER_SUBJECT_MISSING,
    VERIFIED_EMAIL_MISSING,
    IDENTITY_UNVERIFIED,
    PROVIDER_UNAVAILABLE
}
