package com.example.temperate.service.auth.login.limit.exception;

/**
 * 表示登录限流存储不可用或返回不一致状态的基础设施异常。
 *
 * <p>调用方应按安全默认值处理该异常，不能将其误判为普通登录失败或无限重试。</p>
 */
public final class LoginRateLimitInfrastructureException extends RuntimeException {

    public LoginRateLimitInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }

    public LoginRateLimitInfrastructureException(String message) {
        super(message);
    }
}
