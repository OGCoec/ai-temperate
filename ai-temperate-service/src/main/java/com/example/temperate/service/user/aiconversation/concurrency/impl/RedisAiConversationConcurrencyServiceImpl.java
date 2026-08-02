package com.example.temperate.service.user.aiconversation.concurrency.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.user.aiconversation.concurrency.AiConversationConcurrencyPermit;
import com.example.temperate.service.user.aiconversation.concurrency.AiConversationConcurrencyService;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.security.AiConversationIdempotencyHasher;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 使用两个 Redis ZSET 和单个 Lua 原子限制全部实例的全局及单用户模型生成并发。
 */
@Service
public final class RedisAiConversationConcurrencyServiceImpl
        implements AiConversationConcurrencyService {

    private static final long EXPIRY_GRACE_MILLIS = 60_000L;
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT =
            script("lua/ai-conversation/acquire_concurrency.lua");
    private static final DefaultRedisScript<Long> RENEW_SCRIPT =
            script("lua/ai-conversation/renew_concurrency.lua");
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            script("lua/ai-conversation/release_concurrency.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final AiConversationIdempotencyHasher hasher;
    private final AiConversationProperties properties;
    private final Clock clock;
    private final AiConversationMetrics metrics;

    public RedisAiConversationConcurrencyServiceImpl(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            AiConversationIdempotencyHasher hasher,
            AiConversationProperties properties,
            Clock clock,
            AiConversationMetrics metrics) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.hasher = Objects.requireNonNull(hasher);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public Optional<AiConversationConcurrencyPermit> tryAcquire(long userId) {
        HmacIdentifier userIdentifier = hasher.concurrencyUserIdentifier(userId);
        String owner = UUID.randomUUID().toString();
        long now = clock.millis();
        long expiresAt = Math.addExact(
                now, properties.inflightLeaseTtl().toMillis());
        try {
            Long result = redisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    keys(userIdentifier),
                    Long.toString(now),
                    Long.toString(expiresAt),
                    Integer.toString(properties.maxConcurrentGlobal()),
                    Integer.toString(properties.maxConcurrentPerUser()),
                    owner,
                    Long.toString(Math.addExact(
                            expiresAt, EXPIRY_GRACE_MILLIS)));
            if (Long.valueOf(1L).equals(result)) {
                metrics.concurrency("acquired");
                return Optional.of(new AiConversationConcurrencyPermit(
                        userIdentifier, owner));
            }
            metrics.concurrency(Long.valueOf(2L).equals(result)
                    ? "global_rejected" : "user_rejected");
            return Optional.empty();
        } catch (RuntimeException failure) {
            metrics.concurrency("unavailable");
            return Optional.empty();
        }
    }

    @Override
    public boolean renew(AiConversationConcurrencyPermit permit) {
        long expiresAt = Math.addExact(
                clock.millis(), properties.inflightLeaseTtl().toMillis());
        try {
            Long result = redisTemplate.execute(
                    RENEW_SCRIPT,
                    keys(permit.userIdentifier()),
                    permit.owner(),
                    Long.toString(expiresAt),
                    Long.toString(Math.addExact(
                            expiresAt, EXPIRY_GRACE_MILLIS)));
            boolean renewed = Long.valueOf(1L).equals(result);
            if (!renewed) {
                metrics.concurrency("renew_failed");
            }
            return renewed;
        } catch (RuntimeException failure) {
            metrics.concurrency("renew_failed");
            return false;
        }
    }

    @Override
    public void release(AiConversationConcurrencyPermit permit) {
        try {
            redisTemplate.execute(
                    RELEASE_SCRIPT,
                    keys(permit.userIdentifier()),
                    permit.owner());
        } catch (RuntimeException ignoredFailure) {
            // 释放失败由 ZSET score 和 Key 的绝对过期时间收敛，业务结果不能被覆盖。
        }
    }

    private List<String> keys(HmacIdentifier userIdentifier) {
        return List.of(
                keyFactory.aiConversationGlobalConcurrencyKey(),
                keyFactory.aiConversationUserConcurrencyKey(userIdentifier));
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
