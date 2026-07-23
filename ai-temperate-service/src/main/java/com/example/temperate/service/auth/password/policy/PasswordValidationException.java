package com.example.temperate.service.auth.password.policy;

/**
 * 表示密码写入前由统一策略发现的确认不一致或强度不足错误。
 *
 * <p>该异常只携带有限原因枚举，不得包含明文密码、哈希或可用于推断密码内容的细节。</p>
 */
public final class PasswordValidationException extends RuntimeException {

    private final Reason reason;

    public PasswordValidationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        CONFIRMATION_MISMATCH,
        STRENGTH_INSUFFICIENT
    }
}
