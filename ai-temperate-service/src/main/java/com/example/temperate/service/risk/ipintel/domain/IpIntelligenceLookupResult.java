package com.example.temperate.service.risk.ipintel.domain;

/**
 * 表示一次 IP 情报查询结果，并保留本次请求最初是否直接命中 Redis 的事实。
 *
 * <p>等待 single-flight 后读到其他请求刚写入的缓存仍属于本次初始未命中，避免风控层把一次
 * 新查询错误地当作既有缓存决策复用。</p>
 */
public record IpIntelligenceLookupResult(
        IpIntelligenceSnapshot snapshot,
        boolean initialCacheHit) {

    public IpIntelligenceLookupResult {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "IP intelligence lookup snapshot is required.");
        }
    }
}
