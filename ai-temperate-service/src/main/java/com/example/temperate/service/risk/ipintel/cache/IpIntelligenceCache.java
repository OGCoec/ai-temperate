package com.example.temperate.service.risk.ipintel.cache;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSnapshot;
import java.time.Duration;
import java.util.Optional;

/**
 * 定义独立 IP 信用快照缓存和跨实例 single-flight 锁的 Redis 边界。
 */
public interface IpIntelligenceCache {

    Optional<IpIntelligenceSnapshot> find(HmacIdentifier ipDigest);

    void store(HmacIdentifier ipDigest, IpIntelligenceSnapshot snapshot, Duration ttl);

    boolean tryAcquireLookup(HmacIdentifier ipDigest, String owner, Duration ttl);

    void releaseLookup(HmacIdentifier ipDigest, String owner);
}
