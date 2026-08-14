package com.example.temperate.service.bloom;

import com.example.temperate.common.bloom.counting.CountingBloomLayout;
import java.util.List;

/**
 * 该值对象是来向通用 Redis 计数 Bloom 引擎提供经过 RedisKeyFactory 生成的有界命名空间和纯计算布局。
 */
public record CountingBloomNamespace(
        CountingBloomLayout layout,
        String metaKey,
        List<String> bucketKeys,
        List<String> receiptKeys,
        String positiveMutationKey) {

    public CountingBloomNamespace {
        if (layout == null
                || metaKey == null
                || bucketKeys == null
                || receiptKeys == null
                || positiveMutationKey == null
                || bucketKeys.size() != layout.bucketCount()
                || receiptKeys.isEmpty()) {
            throw new IllegalArgumentException("Counting Bloom namespace is incomplete");
        }
        bucketKeys = List.copyOf(bucketKeys);
        receiptKeys = List.copyOf(receiptKeys);
    }
}
