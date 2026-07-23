package com.example.temperate.service.auth.session.authentication.exception;

import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import java.util.Objects;

/**
 * 表示会话认证链路可映射为受控客户端响应的业务异常。
 *
 * <p>`clearCookies` 明确告知 Web 适配层当前错误是否要求销毁浏览器会话材料，避免将暂时性基础设施错误
 * 错误处理为永久登出。</p>
 */
public final class SessionAuthenticationException extends RuntimeException {

    private final SessionAuthenticationErrorCode code;
    private final boolean clearCookies;

    public SessionAuthenticationException(
            SessionAuthenticationErrorCode code, String message, boolean clearCookies) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.clearCookies = clearCookies;
    }

    public SessionAuthenticationException(
            SessionAuthenticationErrorCode code,
            String message,
            boolean clearCookies,
            Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.clearCookies = clearCookies;
    }

    public SessionAuthenticationErrorCode code() {
        return code;
    }

    public boolean clearCookies() {
        return clearCookies;
    }
}
