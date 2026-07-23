package com.example.temperate.service.auth.passwordreset;

import java.util.Objects;

/**
 * 表示密码重置链路中可安全映射为外部响应的业务异常。
 *
 * <p>异常携带稳定错误码，调用方不得向客户端透传存储、密码或验证码的内部细节。</p>
 */
public final class PasswordResetException extends RuntimeException {

    private final PasswordResetErrorCode code;

    public PasswordResetException(PasswordResetErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code);
    }

    public PasswordResetException(
            PasswordResetErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code);
    }

    public PasswordResetErrorCode code() {
        return code;
    }
}
