package com.example.temperate.common.id.snowflake.component;

import java.nio.ByteBuffer;
import java.security.SecureRandom;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 使用时间戳、机房、工作节点与随机熵生成 128 位二进制资源 ID。
 *
 * <p>不同实例必须配置不同的机房和工作节点组合；同一实例在本地互斥锁内更新时间戳与随机熵。
 * 该工作器不使用 Redis 协调，时钟回拨或同毫秒熵空间耗尽时拒绝沿用旧状态，以避免重复发号。</p>
 */
public class HybridSemaphoreIdWorker {

    private final long datacenterIdBits = 8L;
    private final long workerIdBits = 8L;
    private final long maxDatacenterId = ~(-1L << datacenterIdBits);
    private final long maxWorkerId = ~(-1L << workerIdBits);
    private final long datacenterIdShift = workerIdBits;
    private final long timestampLeftShift = datacenterIdBits + workerIdBits;

    private final long datacenterId;
    private final long workerId;
    private final SecureRandom random = new SecureRandom();

    private long lastTimestamp = -1L;
    private long entropyLow64;

    private final ReentrantLock lock = new ReentrantLock(true); // 使用公平锁

    public HybridSemaphoreIdWorker(long datacenterId, long workerId) {
        if (datacenterId < 0 || datacenterId > maxDatacenterId) {
            throw new IllegalArgumentException(String.format("机房 ID 必须在 0 到 %d 之间", maxDatacenterId));
        }
        if (workerId < 0 || workerId > maxWorkerId) {
            throw new IllegalArgumentException(String.format("机器 ID 必须在 0 到 %d 之间", maxWorkerId));
        }
        this.datacenterId = datacenterId;
        this.workerId = workerId;
    }

    // 时间戳与随机熵状态由实例锁串行更新；时钟回拨直接拒绝发号，避免并发或回拨产生重复 ID。
    public byte[] nextId() {
        lock.lock();
        try {
            long timestamp = System.currentTimeMillis();

            if (timestamp < lastTimestamp) {
                throw new RuntimeException("系统时钟回拨，拒绝发号！");
            }

            if (timestamp == lastTimestamp) {
                long randomStride = random.nextInt(1024) + 1;
                if (entropyLow64 >= 0) {
                    entropyLow64 += randomStride;
                    if (entropyLow64 < 0) {
                        timestamp = tilNextMillis(lastTimestamp);
                        entropyLow64 = random.nextLong() & ~(1L << 62);
                    }
                } else {
                    entropyLow64 += randomStride;
                    if (entropyLow64 >= 0) {
                        timestamp = tilNextMillis(lastTimestamp);
                        entropyLow64 = random.nextLong() & ~(1L << 62);
                    }
                }
            } else {
                entropyLow64 = random.nextLong() & ~(1L << 62);
            }

            lastTimestamp = timestamp;

            long high64 = (timestamp << timestampLeftShift)
                    | (datacenterId << datacenterIdShift)
                    | workerId;

            return toByteArray(high64, entropyLow64);

        } finally {
            lock.unlock();
        }
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    private byte[] toByteArray(long high, long low) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(high);
        buffer.putLong(low);
        return buffer.array();
    }

}
