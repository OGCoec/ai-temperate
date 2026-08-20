package com.example.temperate.service.auth.oauth.phone;

/**
 * 表示 OAuth 补手机号流程因发送频率、冷却冲突或手机号探测被临时封禁。
 */
public final class OAuthPhoneRiskException extends RuntimeException {

    public OAuthPhoneRiskException(String message) {
        super(message);
    }

    public OAuthPhoneRiskException(String message, Throwable cause) {
        super(message, cause);
    }
}
