package com.example.temperate.service.user.apikey.authentication;

import com.example.temperate.service.user.apikey.cache.ApiKeyAuthenticationCache.CachedCredential;

/**
 * 该服务是来只在认证缓存未命中时以只读 PostgreSQL 事务加载 Key 生命周期和全部有效模型授权，避免缓存命中也提前占用数据库连接。
 */
public interface ApiKeyAuthenticationDatabaseService {

    CachedCredential load(byte[] keyDigest);
}
