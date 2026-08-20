package com.example.temperate.service.auth.oauth.flow;

/**
 * 表示 OAuth Redis 状态机拒绝访问或无法完成原子转换。
 */
public final class OAuthFlowException extends RuntimeException {

    private final OAuthFlowErrorCode code;

    public OAuthFlowException(OAuthFlowErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public OAuthFlowException(OAuthFlowErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public OAuthFlowErrorCode code() {
        return code;
    }
}
