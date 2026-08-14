package com.example.temperate.service.user.apikey.authentication;

/**
 * 该异常是来把格式错误、不存在、禁用、软删除和过期统一收敛为 invalid_api_key，避免泄露凭证生命周期差异。
 */
public final class ApiKeyAuthenticationException extends RuntimeException {

    public ApiKeyAuthenticationException() {
        super("Invalid API Key");
    }
}
