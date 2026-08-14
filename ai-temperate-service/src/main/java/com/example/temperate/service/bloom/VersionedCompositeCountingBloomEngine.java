package com.example.temperate.service.bloom;

import com.example.temperate.common.bloom.counting.CountingBloomLayout;
import java.time.Duration;
import java.util.List;

/**
 * 该服务是来维护双版本、双字段成组 Receipt 的通用计数 Bloom，并把 Redis 状态机与具体身份领域隔离开。
 *
 * <p>一个 Receipt 可以原子携带必填主字段和可选次字段；任一计数器异常时整组更新失败，调用方不得把部分结果当作成功。
 */
public interface VersionedCompositeCountingBloomEngine {

    LookupResult lookup(
            Namespace namespace,
            Field field,
            String protectedIdentifier);

    MutationResult addAll(
            Namespace namespace,
            List<CompositeRecord> records);

    MutationResult removeAll(
            Namespace namespace,
            List<CompositeRecord> records);

    boolean tryAcquireBuildLease(
            Namespace namespace,
            String leaseToken,
            Duration ttl);

    boolean renewBuildLease(
            Namespace namespace,
            String leaseToken,
            Duration ttl);

    String beginBuild(
            Namespace namespace,
            Generation generation);

    void markReady(
            Namespace namespace,
            String generation);

    void activate(
            Namespace namespace,
            String generation);

    void cleanupGeneration(Generation generation);

    void markDegraded(Namespace namespace, String reason);

    void releaseBuildLease(Namespace namespace, String leaseToken);

    /**
     * 主字段和次字段是存储层的稳定位置，不携带邮箱、手机号等领域含义。
     */
    enum Field {
        PRIMARY,
        SECONDARY
    }

    enum LookupResult {
        DEFINITELY_NOT_PRESENT,
        MAYBE_PRESENT,
        UNAVAILABLE
    }

    enum MutationResult {
        UPDATED,
        ALREADY_APPLIED,
        OVERFLOW,
        UNDERFLOW,
        CAPACITY_EXCEEDED,
        UNAVAILABLE
    }

    /**
     * 命名空间携带稳定控制 Key 和容量参数，所有 Key 必须由领域包装器通过 RedisKeyFactory 生成。
     */
    record Namespace(
            CountingBloomLayout layout,
            int buildBatchSize,
            int receiptShards,
            int maximumElements,
            String controlKey,
            String buildLeaseKey) {

        public Namespace {
            if (layout == null
                    || buildBatchSize < 1
                    || buildBatchSize > 2_000
                    || receiptShards < 1
                    || receiptShards > 1_000
                    || Integer.bitCount(receiptShards) != 1
                    || maximumElements < 1
                    || controlKey == null
                    || controlKey.isBlank()
                    || buildLeaseKey == null
                    || buildLeaseKey.isBlank()) {
                throw new IllegalArgumentException(
                        "Versioned composite Bloom namespace is incomplete");
            }
        }
    }

    /**
     * 构建代次显式列出全部分片 Key，避免通用引擎在内部拼接 Redis Key。
     */
    record Generation(
            String name,
            String metaKey,
            List<String> primaryBucketKeys,
            List<String> secondaryBucketKeys,
            List<String> receiptKeys) {

        public Generation {
            if (name == null
                    || name.isBlank()
                    || metaKey == null
                    || metaKey.isBlank()
                    || primaryBucketKeys == null
                    || secondaryBucketKeys == null
                    || receiptKeys == null
                    || primaryBucketKeys.isEmpty()
                    || primaryBucketKeys.size() != secondaryBucketKeys.size()
                    || receiptKeys.isEmpty()) {
                throw new IllegalArgumentException(
                        "Versioned composite Bloom generation is incomplete");
            }
            primaryBucketKeys = List.copyOf(primaryBucketKeys);
            secondaryBucketKeys = List.copyOf(secondaryBucketKeys);
            receiptKeys = List.copyOf(receiptKeys);
        }
    }

    /**
     * Receipt 标识负责元素级幂等；位置计算只使用已经经过领域隔离 HMAC 保护的字段标识。
     */
    record CompositeRecord(
            String receiptIdentifier,
            int receiptShard,
            String primaryIdentifier,
            String secondaryIdentifier) {

        public CompositeRecord {
            if (receiptIdentifier == null
                    || receiptIdentifier.isBlank()
                    || receiptShard < 0
                    || primaryIdentifier == null
                    || primaryIdentifier.isBlank()
                    || (secondaryIdentifier != null
                    && secondaryIdentifier.isBlank())) {
                throw new IllegalArgumentException(
                        "Versioned composite Bloom record is invalid");
            }
        }
    }
}
