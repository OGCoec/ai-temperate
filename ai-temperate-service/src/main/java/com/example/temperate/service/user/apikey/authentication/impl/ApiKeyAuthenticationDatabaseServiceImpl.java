package com.example.temperate.service.user.apikey.authentication.impl;

import com.example.temperate.mapper.ai.UserApiKeyMapper;
import com.example.temperate.mapper.ai.UserApiKeyModelMapper;
import com.example.temperate.model.ai.entity.UserApiKey;
import com.example.temperate.service.user.apikey.authentication.ApiKeyAuthenticationDatabaseService;
import com.example.temperate.service.user.apikey.cache.ApiKeyAuthenticationCache.CachedCredential;
import java.util.LinkedHashSet;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 该实现是来在一个只读事务中加载 API Key 主记录和最多五百个有效模型 ID，数据库仍是认证缓存的唯一事实来源。
 */
@Service
public final class ApiKeyAuthenticationDatabaseServiceImpl
        implements ApiKeyAuthenticationDatabaseService {

    private final UserApiKeyMapper apiKeyMapper;
    private final UserApiKeyModelMapper modelGrantMapper;

    public ApiKeyAuthenticationDatabaseServiceImpl(
            UserApiKeyMapper apiKeyMapper,
            UserApiKeyModelMapper modelGrantMapper) {
        this.apiKeyMapper = Objects.requireNonNull(apiKeyMapper);
        this.modelGrantMapper = Objects.requireNonNull(modelGrantMapper);
    }

    @Override
    @Transactional(readOnly = true)
    public CachedCredential load(byte[] keyDigest) {
        UserApiKey entity = apiKeyMapper.findByDigest(keyDigest);
        if (entity == null) {
            return null;
        }
        return new CachedCredential(
                2,
                entity.getId(),
                entity.getLoginIdentityId(),
                entity.getStatus(),
                entity.getExpiresAt(),
                new LinkedHashSet<>(modelGrantMapper.findActiveModelIds(entity.getId())),
                false);
    }
}
