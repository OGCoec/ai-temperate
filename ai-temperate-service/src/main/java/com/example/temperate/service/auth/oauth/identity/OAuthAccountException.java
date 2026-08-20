package com.example.temperate.service.auth.oauth.identity;

/**
 * 表示 OAuth 账号解析或持久化失败，并仅暴露稳定业务错误码而不泄露身份细节。
 */
public final class OAuthAccountException extends RuntimeException {

    private final OAuthAccountErrorCode code;

    public OAuthAccountException(OAuthAccountErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public OAuthAccountException(
            OAuthAccountErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public OAuthAccountErrorCode code() {
        return code;
    }
}
