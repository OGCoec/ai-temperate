package com.example.temperate.service.user.aiconversation.response.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseControlStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 使用两个短期 Redis String 协调直接 SSE 的 Owner 与用户 Stop，并通过比较删除避免旧流清掉新 Owner。
 */
@Service
public final class RedisAiConversationDirectResponseControlStore
        implements AiConversationDirectResponseControlStore {

    private static final String USER_STOP_VALUE = "USER_STOP";
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            script("lua/ai-conversation/release_lease.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public RedisAiConversationDirectResponseControlStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public void registerOwner(
            HmacIdentifier requestIdentifier,
            String instanceId,
            Duration timeToLive) {
        requireTtl(timeToLive);
        redisTemplate.opsForValue().set(
                keyFactory.aiConversationDirectResponseOwnerKey(requestIdentifier),
                requireInstanceId(instanceId),
                timeToLive);
    }

    @Override
    public Optional<String> findOwner(HmacIdentifier requestIdentifier) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(
                keyFactory.aiConversationDirectResponseOwnerKey(requestIdentifier)));
    }

    @Override
    public boolean requestUserStop(
            HmacIdentifier requestIdentifier,
            Duration timeToLive) {
        requireTtl(timeToLive);
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                keyFactory.aiConversationDirectResponseCancelKey(requestIdentifier),
                USER_STOP_VALUE,
                timeToLive));
    }

    @Override
    public boolean userStopRequested(HmacIdentifier requestIdentifier) {
        return USER_STOP_VALUE.equals(redisTemplate.opsForValue().get(
                keyFactory.aiConversationDirectResponseCancelKey(requestIdentifier)));
    }

    @Override
    public void clearOwner(
            HmacIdentifier requestIdentifier,
            String instanceId) {
        redisTemplate.execute(
                RELEASE_SCRIPT,
                List.of(keyFactory.aiConversationDirectResponseOwnerKey(requestIdentifier)),
                requireInstanceId(instanceId));
    }

    @Override
    public void clearUserStop(HmacIdentifier requestIdentifier) {
        redisTemplate.unlink(
                keyFactory.aiConversationDirectResponseCancelKey(requestIdentifier));
    }

    private static String requireInstanceId(String instanceId) {
        if (instanceId == null
                || !instanceId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException(
                    "AI direct response instance ID is invalid.");
        }
        return instanceId;
    }

    private static void requireTtl(Duration timeToLive) {
        if (timeToLive == null
                || timeToLive.isZero()
                || timeToLive.isNegative()) {
            throw new IllegalArgumentException(
                    "AI direct response control TTL is invalid.");
        }
    }

    private static DefaultRedisScript<Long> script(String path) {
        try {
            String source = new ClassPathResource(path)
                    .getContentAsString(StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(source, Long.class);
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
