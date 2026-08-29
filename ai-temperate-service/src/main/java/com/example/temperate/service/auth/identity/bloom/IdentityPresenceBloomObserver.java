package com.example.temperate.service.auth.identity.bloom;

/**
 * 该接口是来记录身份 Bloom 查询、Redis 批量、更新、误判和后台重建结果，且禁止携带高基数标签。
 */
public interface IdentityPresenceBloomObserver {

    void query(IdentityPresenceKind kind, IdentityPresenceDecision decision);

    void mutation(IdentityPresenceMutationResult result);

    void falsePositive(IdentityPresenceKind kind);

    void buildStarted();

    void buildProgress(long processedElements);

    void buildReady();

    void buildCompleted(long processedElements, long durationNanos);

    void buildFailed(String reason);

    void degraded(String reason);

    void redisOperation(
            String operation,
            String outcome,
            long durationNanos,
            int itemCount);
}
