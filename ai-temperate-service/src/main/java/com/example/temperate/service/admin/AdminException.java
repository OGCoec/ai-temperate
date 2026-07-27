package com.example.temperate.service.admin;

import java.util.Objects;

/**
 * 表示管理员认证边界内的受控业务失败，不携带密码、验证码、原始 Token 或配置文件内容。
 */
public final class AdminException extends RuntimeException {

    private final AdminErrorCode code;
    private final boolean clearFlow;
    private final boolean clearSession;

    public AdminException(AdminErrorCode code, String message) {
        this(code, message, null, false, false);
    }

    public AdminException(AdminErrorCode code, String message, Throwable cause) {
        this(code, message, cause, false, false);
    }

    public AdminException(
            AdminErrorCode code,
            String message,
            Throwable cause,
            boolean clearFlow,
            boolean clearSession) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.clearFlow = clearFlow;
        this.clearSession = clearSession;
    }

    public AdminErrorCode code() {
        return code;
    }

    public boolean clearFlow() {
        return clearFlow;
    }

    public boolean clearSession() {
        return clearSession;
    }
}
