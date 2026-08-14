package com.example.temperate.service.user.apikey.authentication;

import java.util.Set;

/**
 * 该认证主体是来向 `/v1` 安全链传递内部 Key/账号标识、不可逆摘要及快速模型授权集合，不包含原始 Bearer 凭证。
 */
public record ApiKeyPrincipal(
        long apiKeyId,
        long loginIdentityId,
        byte[] keyDigest,
        String digestIdentifier,
        Set<Long> modelIds) {

    public ApiKeyPrincipal {
        keyDigest = keyDigest.clone();
        modelIds = Set.copyOf(modelIds);
    }

    @Override
    public byte[] keyDigest() {
        return keyDigest.clone();
    }
}
