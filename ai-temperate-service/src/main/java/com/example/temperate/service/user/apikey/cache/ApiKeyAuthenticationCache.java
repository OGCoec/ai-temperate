package com.example.temperate.service.user.apikey.cache;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * 该缓存边界是来保存不含原始 Key 的正向认证快照和短期负值，并在数据库提交后按摘要统一失效。
 */
public interface ApiKeyAuthenticationCache {

    CachedCredential get(String digestIdentifier);

    void putPositive(String digestIdentifier, CachedCredential credential);

    void putNegative(String digestIdentifier);

    void invalidate(String digestIdentifier);

    /** 正向快照只用于快速认证，预扣事务必须重新校验数据库真值。 */
    record CachedCredential(
            int schemaVersion,
            long apiKeyId,
            long loginIdentityId,
            int status,
            OffsetDateTime expiresAt,
            Set<Long> modelIds,
            boolean negative) {

        public CachedCredential {
            modelIds = modelIds == null ? Set.of() : Set.copyOf(modelIds);
        }

        public static CachedCredential negativeEntry() {
            return new CachedCredential(1, 0, 0, 0, null, Set.of(), true);
        }
    }
}
