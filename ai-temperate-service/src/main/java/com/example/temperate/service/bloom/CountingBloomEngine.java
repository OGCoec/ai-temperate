package com.example.temperate.service.bloom;

import java.time.Duration;
import java.util.List;

/**
 * 该服务是来统一执行计数 Bloom 的原子查询、Receipt 幂等增减、positive mutation 和构建状态切换，不理解具体业务标识。
 */
public interface CountingBloomEngine {

    LookupResult lookup(CountingBloomNamespace namespace, String protectedIdentifier);

    UpdateResult add(CountingBloomNamespace namespace, String protectedIdentifier);

    long addBatch(
            CountingBloomNamespace namespace,
            List<String> protectedIdentifiers);

    UpdateResult remove(CountingBloomNamespace namespace, String protectedIdentifier);

    void beginPositiveMutation(
            CountingBloomNamespace namespace,
            String mutationId,
            String protectedIdentifier);

    void finishPositiveMutation(
            CountingBloomNamespace namespace,
            String mutationId,
            boolean safeToComplete);

    void initializeBuilding(CountingBloomNamespace namespace, BuildFence fence);

    long addBuildBatch(
            CountingBloomNamespace namespace,
            List<String> protectedIdentifiers,
            BuildFence fence);

    long recoverPositiveMutations(
            CountingBloomNamespace namespace,
            BuildFence fence);

    boolean validateAndActivate(
            CountingBloomNamespace namespace,
            long minimumElementCount,
            long maximumElementCount,
            BuildFence fence);

    void markDegraded(CountingBloomNamespace namespace, String reason);

    void markBuildDegraded(
            CountingBloomNamespace namespace,
            BuildFence fence,
            String reason);

    /** 构建 Fence 同时携带当前 Lease 值和单调 epoch，任何旧 Leader 的专属写入都必须被 Lua 拒绝。 */
    record BuildFence(
            String leaderKey,
            String leaseValue,
            long epoch,
            Duration leaseTtl) {

        public BuildFence {
            if (leaderKey == null || leaderKey.isBlank()
                    || leaseValue == null || leaseValue.isBlank()
                    || epoch <= 0
                    || leaseTtl == null
                    || leaseTtl.isNegative()
                    || leaseTtl.isZero()) {
                throw new IllegalArgumentException("Counting Bloom build fence is invalid");
            }
        }
    }

    enum LookupResult {
        DEFINITELY_NOT_PRESENT,
        MAYBE_PRESENT,
        UNAVAILABLE
    }

    enum UpdateResult {
        UPDATED,
        ALREADY_APPLIED,
        UNAVAILABLE
    }
}
