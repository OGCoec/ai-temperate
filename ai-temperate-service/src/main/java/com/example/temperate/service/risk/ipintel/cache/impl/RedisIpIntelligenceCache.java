package com.example.temperate.service.risk.ipintel.cache.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.ipintel.cache.IpIntelligenceCache;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 使用 Redis String JSON 缓存标准化 IP 情报，并以带所有者值的短期锁抑制缓存击穿。
 *
 * <p>解锁由 Lua 先比较随机所有者再删除，防止超时后旧请求误删新请求已经取得的锁。</p>
 */
@Component
public final class RedisIpIntelligenceCache implements IpIntelligenceCache {

    private static final RedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final ObjectMapper objectMapper;

    public RedisIpIntelligenceCache(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            ObjectMapper objectMapper) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public Optional<IpIntelligenceSnapshot> find(HmacIdentifier ipDigest) {
        String value = redisTemplate.opsForValue().get(
                keyFactory.ipIntelligenceCacheKey(ipDigest));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, IpIntelligenceSnapshot.class));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // 旧版本或损坏缓存不能影响认证链；删除后允许重新查询供应商。
            redisTemplate.unlink(keyFactory.ipIntelligenceCacheKey(ipDigest));
            return Optional.empty();
        }
    }

    @Override
    public void store(
            HmacIdentifier ipDigest,
            IpIntelligenceSnapshot snapshot,
            Duration ttl) {
        try {
            redisTemplate.opsForValue().set(
                    keyFactory.ipIntelligenceCacheKey(ipDigest),
                    objectMapper.writeValueAsString(snapshot),
                    Objects.requireNonNull(ttl));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("IP intelligence cache serialization failed.", exception);
        }
    }

    @Override
    public boolean tryAcquireLookup(
            HmacIdentifier ipDigest,
            String owner,
            Duration ttl) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                keyFactory.ipIntelligenceSingleFlightKey(ipDigest),
                Objects.requireNonNull(owner),
                Objects.requireNonNull(ttl));
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void releaseLookup(HmacIdentifier ipDigest, String owner) {
        redisTemplate.execute(
                RELEASE_LOCK,
                List.of(keyFactory.ipIntelligenceSingleFlightKey(ipDigest)),
                Objects.requireNonNull(owner));
    }
}
