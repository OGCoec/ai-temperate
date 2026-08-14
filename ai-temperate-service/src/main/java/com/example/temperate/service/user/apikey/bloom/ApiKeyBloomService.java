package com.example.temperate.service.user.apikey.bloom;

/**
 * 该领域包装器是来让 API Key 生命周期和认证使用通用计数 Bloom，而不在业务代码中拼接 Redis Key 或操作计数器。
 */
public interface ApiKeyBloomService {

    LookupResult lookup(byte[] digest);

    PositiveMutation beginPositiveMutation(byte[] digest);

    void commitPositiveMutation(PositiveMutation mutation);

    void rollbackPositiveMutation(PositiveMutation mutation);

    void remove(byte[] digest);

    enum LookupResult {
        DEFINITELY_NOT_PRESENT,
        MAYBE_PRESENT,
        UNAVAILABLE
    }

    /** mutationId 仅是 Redis 幂等收敛标识，不包含或还原原始 API Key。 */
    record PositiveMutation(String mutationId, byte[] digest) {
    }
}
