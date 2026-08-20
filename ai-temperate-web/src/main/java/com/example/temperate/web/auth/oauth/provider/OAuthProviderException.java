package com.example.temperate.web.auth.oauth.provider;

/**
 * 表示浏览器或原生 Provider 证明无法转换为可信第三方身份。
 */
public final class OAuthProviderException extends RuntimeException {

    private final OAuthProviderErrorCode code;

    public OAuthProviderException(OAuthProviderErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public OAuthProviderException(
            OAuthProviderErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public OAuthProviderErrorCode code() {
        return code;
    }
}
