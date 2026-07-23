package com.example.temperate.service.auth.login.exception;

import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import java.util.Objects;

/**
 * 表示登录业务链路中可被 Web 层映射为受控响应的异常。
 *
 * <p>异常携带稳定错误码而非敏感认证细节；调用方应按错误码决定响应、重试或清理会话，不应透传底层异常信息。</p>
 */
public final class LoginException extends RuntimeException {

    private final LoginErrorCode code;

    public LoginException(LoginErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public LoginException(LoginErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public LoginErrorCode code() {
        return code;
    }
}
