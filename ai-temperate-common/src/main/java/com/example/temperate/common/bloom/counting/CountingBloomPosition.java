package com.example.temperate.common.bloom.counting;

/**
 * 表示计数布隆过滤器中一个经过分片换算的计数器位置。
 *
 * @param bucketNumber 计数器所在的 Bucket 编号
 * @param byteOffset 计数器在 Redis String Bucket 内的字节偏移量
 */
public record CountingBloomPosition(int bucketNumber, int byteOffset) {

    public CountingBloomPosition {
        if (bucketNumber < 0 || byteOffset < 0) {
            throw new IllegalArgumentException("Bloom position must not be negative.");
        }
    }
}
