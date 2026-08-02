package com.example.temperate.service.user.profile.cache.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.profile.cache.UserProfileCacheStore;
import com.example.temperate.service.user.profile.cache.UserProfileCacheValue;
import com.example.temperate.service.user.profile.cache.security.UserProfileCacheIdProtector;
import com.example.temperate.service.user.profile.config.UserProfileCacheProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 使用单个 Redis String 保存普通用户资料的版本化明文 JSON 快照。
 *
 * <p>Redis 读取、解析和写入异常全部按缓存未命中处理，保证数据库仍可作为事实来源；缓存 Key 只包含经
 * AES-256-KWP 保护的用户标识，日志和指标不记录用户 ID、Key 或 Value。</p>
 */
@Component
public final class RedisUserProfileCacheStore implements UserProfileCacheStore {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedisUserProfileCacheStore.class);
    private static final int WARNING_BYTES = 10 * 1024;
    private static final int ABSOLUTE_BYTES = 64 * 1024;

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final UserProfileCacheIdProtector idProtector;
    private final UserProfileCacheProperties properties;
    private final ObjectMapper objectMapper;
    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter readFailureCounter;
    private final Counter writeFailureCounter;
    private final Counter corruptCounter;
    private final Counter warningCounter;
    private final Counter rejectedCounter;

    public RedisUserProfileCacheStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            UserProfileCacheIdProtector idProtector,
            UserProfileCacheProperties properties,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.idProtector = Objects.requireNonNull(idProtector);
        this.properties = Objects.requireNonNull(properties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        MeterRegistry registry = Objects.requireNonNull(meterRegistry);
        this.hitCounter = registry.counter("user.profile.cache.hit");
        this.missCounter = registry.counter("user.profile.cache.miss");
        this.readFailureCounter = registry.counter("user.profile.cache.read.failure");
        this.writeFailureCounter = registry.counter("user.profile.cache.write.failure");
        this.corruptCounter = registry.counter("user.profile.cache.corrupt");
        this.warningCounter = registry.counter("user.profile.cache.size.warning");
        this.rejectedCounter = registry.counter("user.profile.cache.size.rejected");
    }

    @Override
    public Optional<UserProfileCacheValue> find(long userId) {
        String cacheKey = cacheKey(userId);
        String json;
        try {
            json = redisTemplate.opsForValue().get(cacheKey);
        } catch (RuntimeException exception) {
            readFailureCounter.increment();
            LOGGER.warn(
                    "event=user_profile_cache_read_failed operation=get cause={}",
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
        if (json == null) {
            missCounter.increment();
            return Optional.empty();
        }
        try {
            UserProfileCacheValue value =
                    objectMapper.readValue(json, UserProfileCacheValue.class);
            hitCounter.increment();
            return Optional.of(value);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            corruptCounter.increment();
            LOGGER.warn("event=user_profile_cache_snapshot_rejected reason=invalid_json");
            unlinkCorrupt(cacheKey);
            return Optional.empty();
        }
    }

    @Override
    public void put(long userId, UserProfileCacheValue value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            String json = objectMapper.writeValueAsString(value);
            int bytes = json.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > ABSOLUTE_BYTES) {
                rejectedCounter.increment();
                LOGGER.warn(
                        "event=user_profile_cache_write_rejected reason=size bytes={}",
                        bytes);
                return;
            }
            if (bytes > WARNING_BYTES) {
                warningCounter.increment();
                LOGGER.warn("event=user_profile_cache_snapshot_large bytes={}", bytes);
            }
            redisTemplate.opsForValue().set(cacheKey(userId), json, randomizedTtl());
        } catch (JsonProcessingException | RuntimeException exception) {
            writeFailureCounter.increment();
            LOGGER.warn(
                    "event=user_profile_cache_write_failed operation=set cause={}",
                    exception.getClass().getSimpleName());
        }
    }

    @Override
    public void evict(long userId) {
        redisTemplate.unlink(cacheKey(userId));
    }

    @Override
    public void evict(Collection<Long> userIds) {
        List<String> cacheKeys = userIds.stream()
                .distinct()
                .map(this::cacheKey)
                .toList();
        if (!cacheKeys.isEmpty()) {
            // 单批用户来源于最多五百条历史退款记录，使用一次多 Key UNLINK，禁止逐用户 Redis I/O。
            redisTemplate.unlink(cacheKeys);
        }
    }

    private String cacheKey(long userId) {
        return keyFactory.userProfileKey(idProtector.protect(userId));
    }

    private void unlinkCorrupt(String cacheKey) {
        try {
            redisTemplate.unlink(cacheKey);
        } catch (RuntimeException exception) {
            readFailureCounter.increment();
            LOGGER.warn(
                    "event=user_profile_cache_read_failed operation=unlink cause={}",
                    exception.getClass().getSimpleName());
        }
    }

    private Duration randomizedTtl() {
        long minimumMillis = properties.minimumTtl().toMillis();
        long maximumMillis = properties.maximumTtl().toMillis();
        if (minimumMillis == maximumMillis) {
            return Duration.ofMillis(minimumMillis);
        }
        return Duration.ofMillis(
                ThreadLocalRandom.current().nextLong(minimumMillis, maximumMillis + 1));
    }
}
