package com.example.temperate.service.user.apikey.authentication;

/**
 * 该异常是来区分 API Key 无效与数据库等认证事实来源不可用，使安全链失败关闭为 503 而不是错误伪装成 401 或 500。
 */
public final class ApiKeyAuthenticationInfrastructureException extends RuntimeException {

    public ApiKeyAuthenticationInfrastructureException(Throwable cause) {
        super("API Key authentication infrastructure is unavailable", cause);
    }
}
