package com.example.temperate.service.user.apikey.authentication;

/**
 * 该服务是来按格式、HMAC、Bloom、Redis 缓存和 PostgreSQL 的固定顺序懒加载认证外部 API Key。
 */
public interface ApiKeyAuthenticationService {

    ApiKeyPrincipal authenticate(String plaintextApiKey);
}
