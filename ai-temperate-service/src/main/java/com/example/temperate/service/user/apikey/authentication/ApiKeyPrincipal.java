package com.example.temperate.service.user.apikey.authentication;

import java.util.Objects;
import java.util.Set;

/**
 * 该认证主体是来向 `/v1` 安全链传递内部 Key/账号标识、不可逆摘要及快速模型授权集合，不包含原始 Bearer 凭证。
 */
public record ApiKeyPrincipal(
        byte[] apiKeyId,
        long loginIdentityId,
        byte[] keyDigest,
        String digestIdentifier,
        Set<Long> modelIds) {

    public ApiKeyPrincipal {
        apiKeyId = Objects.requireNonNull(apiKeyId, "apiKeyId").clone();
        keyDigest = Objects.requireNonNull(keyDigest, "keyDigest").clone();
        modelIds = Set.copyOf(Objects.requireNonNull(modelIds, "modelIds"));
        if (apiKeyId.length != 16 || keyDigest.length != 32) {
            throw new IllegalArgumentException("API Key principal binary fields are invalid");
        }
    }

    @Override
    public byte[] apiKeyId() {
        return apiKeyId.clone();
    }

    @Override
    public byte[] keyDigest() {
        return keyDigest.clone();
    }
}
