package com.example.temperate.service.auth.session.authentication.enums;

/**
 * 枚举会话认证、刷新和登出流程的稳定业务错误码。
 */
public enum SessionAuthenticationErrorCode {
    INVALID_INPUT,
    ACCESS_TOKEN_REQUIRED,
    ACCESS_TOKEN_INVALID,
    ACCESS_TOKEN_EXPIRED,
    REFRESH_TOKEN_REQUIRED,
    REFRESH_TOKEN_INVALID,
    DEVICE_MISMATCH,
    CSRF_INVALID,
    PREAUTH_REQUIRED,
    SESSION_MISMATCH,
    ACCOUNT_UNAVAILABLE,
    INFRASTRUCTURE_UNAVAILABLE
}
