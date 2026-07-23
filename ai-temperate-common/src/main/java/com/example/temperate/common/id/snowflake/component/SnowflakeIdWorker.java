package com.example.temperate.common.id.snowflake.component;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Twitter的分布式自增ID雪花算法 Snowflake
 * 结构：0 - 41位时间戳 - 5位数据中心ID - 5位机器ID - 12位序列号
 */
public class SnowflakeIdWorker {

    // ============================== 基础常量定义 ==============================

    private final long twepoch = 1767225600000L;
    private final long workerIdBits = 5L;
    private final long datacenterIdBits = 5L;
    private final long maxWorkerId = ~(-1L << workerIdBits);
    private final long maxDatacenterId = ~(-1L << datacenterIdBits);
    private final long sequenceBits = 12L;
    private final long workerIdShift = sequenceBits;
    private final long datacenterIdShift = sequenceBits + workerIdBits;
    private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits;
    private final long sequenceMask = ~(-1L << sequenceBits);

    // ============================== 运行时的状态变量 ==============================

    private long workerId;
    private long datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    private final StringRedisTemplate stringRedisTemplate;
    private final ReentrantLock lock = new ReentrantLock(true); // 使用公平锁

    // ============================== 构造函数 ==============================

    public SnowflakeIdWorker(long workerId, long datacenterId, StringRedisTemplate stringRedisTemplate) {
        if (workerId > maxWorkerId || workerId < 0) {
            throw new IllegalArgumentException(
                    String.format("worker Id can't be greater than %d or less than 0", maxWorkerId));
        }
        if (datacenterId > maxDatacenterId || datacenterId < 0) {
            throw new IllegalArgumentException(
                    String.format("datacenter Id can't be greater than %d or less than 0", maxDatacenterId));
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // ============================== 核心发号逻辑 ==============================

    /**
     * 获得下一个 ID (使用本地公平锁保证并发安全)
     */
    // 序列号和 lastTimestamp 必须在同一把锁内推进；同毫秒耗尽时等待下一毫秒，而不是回绕复用 ID。
    public long nextId() {
        lock.lock();
        try {
            long timestamp = timeGen();

            if (timestamp < lastTimestamp) {
                throw new RuntimeException("Clock moved backwards.");
            }

            if (lastTimestamp == timestamp) {
                sequence = (sequence + 1) & sequenceMask;
                if (sequence == 0) {
                    timestamp = tilNextMillis(lastTimestamp);
                }
            } else {
                sequence = 0L;
            }

            lastTimestamp = timestamp;

            return ((timestamp - twepoch) << timestampLeftShift)
                    | (datacenterId << datacenterIdShift)
                    | (workerId << workerIdShift)
                    | sequence;
        } finally {
            lock.unlock();
        }
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    private long timeGen() {
        return System.currentTimeMillis();
    }
}
