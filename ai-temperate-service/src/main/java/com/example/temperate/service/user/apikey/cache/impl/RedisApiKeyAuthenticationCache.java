package com.example.temperate.service.user.apikey.cache.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.user.apikey.cache.ApiKeyAuthenticationCache;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 该实现是来以 Redis String + JSON 懒加载 API Key 认证快照；Redis 故障或损坏值按未命中处理，绝不阻断数据库回源。
 */
@Service
public final class RedisApiKeyAuthenticationCache implements ApiKeyAuthenticationCache {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedisApiKeyAuthenticationCache.class);
    private static final int SCHEMA_VERSION = 1;
    private static final int WARNING_BYTES = 10 * 1024;
    private static final int ABSOLUTE_BYTES = 64 * 1024;

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final ObjectMapper objectMapper;
    private final ApiKeyProperties properties;
    private final MeterRegistry meterRegistry;

    public RedisApiKeyAuthenticationCache(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            ObjectMapper objectMapper,
            ApiKeyProperties properties,
            MeterRegistry meterRegistry) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.properties = Objects.requireNonNull(properties);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    public CachedCredential get(String digestIdentifier) {
        String key = cacheKey(digestIdentifier);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                counter("miss");
                return null;
            }
            CachedCredential value = objectMapper.readValue(json, CachedCredential.class);
            if (value.schemaVersion() != SCHEMA_VERSION) {
                counter("corrupt");
                redisTemplate.unlink(key);
                return null;
            }
            counter(value.negative() ? "negative_hit" : "positive_hit");
            return value;
        } catch (JsonProcessingException | RuntimeException exception) {
            counter("read_failure");
            LOGGER.warn(
                    "event=api_key_auth_cache_read_failed traceId={} cause={}",
                    traceId(),
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public void putPositive(String digestIdentifier, CachedCredential credential) {
        write(digestIdentifier, credential, randomized(
                properties.getAuthCache().getMinimumTtl(),
                properties.getAuthCache().getMaximumTtl()));
    }

    @Override
    public void putNegative(String digestIdentifier) {
        write(digestIdentifier, CachedCredential.negativeEntry(), randomized(
                properties.getAuthCache().getNegativeMinimumTtl(),
                properties.getAuthCache().getNegativeMaximumTtl()));
    }

    @Override
    public void invalidate(String digestIdentifier) {
        RuntimeException lastFailure = null;
        String key = cacheKey(digestIdentifier);
        // 提交后删除允许三次有界幂等重试；全部失败时只依赖正向 TTL 收敛，绝不能反向影响已经提交的 PostgreSQL 事务。
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                redisTemplate.unlink(key);
                counter(attempt == 0 ? "invalidate" : "invalidate_retried");
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        counter("invalidate_failure");
        LOGGER.warn(
                "event=api_key_auth_cache_invalidate_failed traceId={} cause={}",
                traceId(),
                lastFailure == null ? "Unknown" : lastFailure.getClass().getSimpleName());
    }

    private void write(String identifier, CachedCredential value, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(value);
            int bytes = json.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > ABSOLUTE_BYTES) {
                counter("size_rejected");
                LOGGER.warn(
                        "event=api_key_auth_cache_write_rejected traceId={} reason=size bytes={}",
                        traceId(),
                        bytes);
                return;
            }
            if (bytes > WARNING_BYTES) {
                counter("size_warning");
                LOGGER.warn(
                        "event=api_key_auth_cache_value_large traceId={} bytes={}",
                        traceId(),
                        bytes);
            }
            redisTemplate.opsForValue().set(cacheKey(identifier), json, ttl);
            counter(value.negative() ? "negative_write" : "positive_write");
        } catch (JsonProcessingException | RuntimeException exception) {
            counter("write_failure");
            LOGGER.warn(
                    "event=api_key_auth_cache_write_failed traceId={} cause={}",
                    traceId(),
                    exception.getClass().getSimpleName());
        }
    }

    private String cacheKey(String digestIdentifier) {
        return keyFactory.apiKeyAuthenticationCacheKey(
                HmacIdentifier.fromProtectedValue(digestIdentifier));
    }

    private static Duration randomized(Duration minimum, Duration maximum) {
        long minMillis = minimum.toMillis();
        long maxMillis = maximum.toMillis();
        if (minMillis == maxMillis) {
            return minimum;
        }
        return Duration.ofMillis(
                ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1));
    }

    private void counter(String result) {
        meterRegistry.counter("api.key.auth.cache", "result", result).increment();
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? "background" : value;
    }
}
