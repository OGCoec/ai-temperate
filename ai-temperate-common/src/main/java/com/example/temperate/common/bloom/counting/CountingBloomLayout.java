package com.example.temperate.common.bloom.counting;

import cn.hutool.core.util.HashUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 负责把受保护标识稳定映射为计数布隆过滤器的分片计数器位置。
 *
 * <p>该类型只处理通用哈希与 Bucket 布局，不读取 Redis，也不理解邮箱、手机号等业务语义。调用方必须
 * 先使用带服务端密钥的 HMAC 保护低熵标识，再把保护值传入本布局。</p>
 */
public final class CountingBloomLayout {

    private static final int MIN_HASH_COUNT = 4;
    private static final int MAX_HASH_COUNT = 25;
    // 首期参数明确使用一百万个一字节计数器，约为 0.95 MiB，不能按严格二进制 MiB 将其拒绝。
    private static final int MIN_BUCKET_BYTES = 1_000_000;
    private static final int MAX_BUCKET_BYTES = 4 * 1024 * 1024;

    private final int capacity;
    private final int hashCount;
    private final int counterBytes;
    private final int countersPerBucket;
    private final int bucketCount;

    public CountingBloomLayout(
            int capacity,
            int hashCount,
            int counterBytes,
            int countersPerBucket) {
        if (capacity < 200 || capacity < hashCount) {
            throw new IllegalArgumentException("Bloom capacity must be at least 200 and hashCount.");
        }
        if (hashCount < MIN_HASH_COUNT || hashCount > MAX_HASH_COUNT) {
            throw new IllegalArgumentException("Bloom hashCount must be between 4 and 25.");
        }
        if (counterBytes != 1 && counterBytes != 2) {
            throw new IllegalArgumentException("Bloom counterBytes must be 1 or 2.");
        }
        if (countersPerBucket <= 0) {
            throw new IllegalArgumentException("Bloom countersPerBucket must be positive.");
        }
        long configuredBucketBytes = Math.multiplyExact(
                (long) countersPerBucket, counterBytes);
        if (configuredBucketBytes < MIN_BUCKET_BYTES
                || configuredBucketBytes > MAX_BUCKET_BYTES) {
            throw new IllegalArgumentException(
                    "Bloom Bucket must contain between 1,000,000 bytes and 4 MiB of counters.");
        }
        this.capacity = capacity;
        this.hashCount = hashCount;
        this.counterBytes = counterBytes;
        this.countersPerBucket = countersPerBucket;
        this.bucketCount = Math.ceilDiv(capacity, countersPerBucket);
    }

    /**
     * 使用双哈希生成固定数量且互不重复的位置。
     *
     * <p>极少数哈希碰撞通过确定性的线性探测消除，避免一次新增对同一个计数器重复加值，从而保持新增与
     * 删除的计数对称性。</p>
     */
    public List<CountingBloomPosition> positions(String protectedIdentifier) {
        if (protectedIdentifier == null || protectedIdentifier.isBlank()) {
            throw new IllegalArgumentException("Protected Bloom identifier must not be blank.");
        }
        byte[] bytes = protectedIdentifier.getBytes(StandardCharsets.UTF_8);
        long firstHash = Integer.toUnsignedLong(HashUtil.murmur32(bytes));
        long secondHash = Integer.toUnsignedLong(HashUtil.fnvHash(protectedIdentifier));
        if (secondHash == 0) {
            secondHash = 0x9E3779B9L;
        }

        Set<Long> uniqueIndexes = new LinkedHashSet<>(hashCount);
        for (int index = 0; index < hashCount; index++) {
            long candidate = Math.floorMod(firstHash + index * secondHash, capacity);
            // 线性探测只处理同一元素内部的极少数重复位置，不改变不同请求之间的共享状态。
            while (!uniqueIndexes.add(candidate)) {
                candidate = (candidate + 1) % capacity;
            }
        }

        List<CountingBloomPosition> positions = new ArrayList<>(hashCount);
        for (long counterIndex : uniqueIndexes) {
            int bucketNumber = (int) (counterIndex / countersPerBucket);
            int localCounterIndex = (int) (counterIndex % countersPerBucket);
            positions.add(new CountingBloomPosition(
                    bucketNumber,
                    Math.multiplyExact(localCounterIndex, counterBytes)));
        }
        return List.copyOf(positions);
    }

    public int capacity() {
        return capacity;
    }

    public int hashCount() {
        return hashCount;
    }

    public int counterBytes() {
        return counterBytes;
    }

    public int countersPerBucket() {
        return countersPerBucket;
    }

    public int bucketCount() {
        return bucketCount;
    }

    /**
     * 返回指定 Bucket 的实际字节长度，最后一个 Bucket 可以小于配置的完整 Bucket。
     */
    public int bucketByteLength(int bucketNumber) {
        if (bucketNumber < 0 || bucketNumber >= bucketCount) {
            throw new IllegalArgumentException("Bloom Bucket number is out of range.");
        }
        int firstCounter = Math.multiplyExact(bucketNumber, countersPerBucket);
        int remainingCounters = capacity - firstCounter;
        return Math.multiplyExact(Math.min(countersPerBucket, remainingCounters), counterBytes);
    }
}
