package com.example.temperate.service.auth.identity.bloom;

import com.example.temperate.common.bloom.counting.CountingBloomLayout;

/**
 * 定义已注册邮箱与手机号计数布隆过滤器的容量、分片和数据库构建批量边界。
 */
public record IdentityPresenceBloomSettings(
        boolean enabled,
        int capacity,
        int hashCount,
        int counterBytes,
        int countersPerBucket,
        int buildBatchSize,
        int receiptShards,
        int maximumElements) {

    public IdentityPresenceBloomSettings {
        new CountingBloomLayout(capacity, hashCount, counterBytes, countersPerBucket);
        if (buildBatchSize < 1 || buildBatchSize > 2_000) {
            throw new IllegalArgumentException(
                    "Identity Bloom build batch size must be between 1 and 2000.");
        }
        if (receiptShards < 1
                || receiptShards > 1_000
                || Integer.bitCount(receiptShards) != 1) {
            throw new IllegalArgumentException(
                    "Identity Bloom receipt shards must be a power of two not exceeding 1000.");
        }
        if (maximumElements < 1
                || Math.ceilDiv(maximumElements, receiptShards) > 1_000) {
            throw new IllegalArgumentException(
                    "Identity Bloom receipt shards must keep each expected Set at 1000 members or fewer.");
        }
        double estimatedRate = estimatedFalsePositiveRate(
                capacity, hashCount, maximumElements);
        if (estimatedRate > 0.01D) {
            throw new IllegalArgumentException(
                    "Identity Bloom maximum element count exceeds the 1% false-positive target.");
        }
    }

    public double estimatedFalsePositiveRateAtMaximumElements() {
        return estimatedFalsePositiveRate(capacity, hashCount, maximumElements);
    }

    /**
     * 估算达到规划元素上限时非零计数器所占比例，用于在不扫描 Redis Bucket 的情况下观测填充程度。
     */
    public double estimatedCounterOccupancyAtMaximumElements() {
        return occupiedProbability(capacity, hashCount, maximumElements);
    }

    private static double estimatedFalsePositiveRate(
            int capacity, int hashCount, int elementCount) {
        return Math.pow(
                occupiedProbability(capacity, hashCount, elementCount),
                hashCount);
    }

    private static double occupiedProbability(
            int capacity, int hashCount, int elementCount) {
        return 1.0D - Math.exp(-1.0D * hashCount * elementCount / capacity);
    }
}
