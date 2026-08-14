package com.example.temperate.service.user.aiinference.concurrency.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.aiconversation.security.AiConversationIdempotencyHasher;
import com.example.temperate.service.user.aiinference.concurrency.AiInferenceConcurrencyPermit;
import com.example.temperate.service.user.aiinference.concurrency.AiInferenceConcurrencyService;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 该实现是来用共享的账号和全局 ZSET 统一限制所有模型流，并在 API Key 调用时以单个 Lua 原子增加第三个 Key ZSET 门禁。
 */
@Service
public final class RedisAiInferenceConcurrencyServiceImpl
        implements AiInferenceConcurrencyService {

    private static final long EXPIRY_GRACE_MILLIS = 60_000L;
    private static final DefaultRedisScript<Long> ACQUIRE_ACCOUNT =
            script("lua/ai-conversation/acquire_concurrency.lua");
    private static final DefaultRedisScript<Long> RENEW_ACCOUNT =
            script("lua/ai-conversation/renew_concurrency.lua");
    private static final DefaultRedisScript<Long> RELEASE_ACCOUNT =
            script("lua/ai-conversation/release_concurrency.lua");
    private static final DefaultRedisScript<Long> ACQUIRE_API_KEY =
            script("lua/ai-conversation/acquire_api_key_concurrency.lua");
    private static final DefaultRedisScript<Long> RENEW_API_KEY =
            script("lua/ai-conversation/renew_api_key_concurrency.lua");
    private static final DefaultRedisScript<Long> RELEASE_API_KEY =
            script("lua/ai-conversation/release_api_key_concurrency.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final AiConversationIdempotencyHasher hasher;
    private final AiConversationProperties conversationProperties;
    private final ApiKeyProperties apiKeyProperties;
    private final Clock clock;
    private final AiConversationMetrics metrics;

    public RedisAiInferenceConcurrencyServiceImpl(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            AiConversationIdempotencyHasher hasher,
            AiConversationProperties conversationProperties,
            ApiKeyProperties apiKeyProperties,
            Clock clock,
            AiConversationMetrics metrics) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.hasher = Objects.requireNonNull(hasher);
        this.conversationProperties = Objects.requireNonNull(conversationProperties);
        this.apiKeyProperties = Objects.requireNonNull(apiKeyProperties);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public AcquireResult tryAcquireAccount(long loginIdentityId, short weight) {
        requireWeight(weight);
        HmacIdentifier account = hasher.concurrencyUserIdentifier(loginIdentityId);
        String owner = UUID.randomUUID().toString();
        long now = clock.millis();
        long expiresAt = Math.addExact(now, conversationProperties.inflightLeaseTtl().toMillis());
        try {
            Long result = redisTemplate.execute(
                    ACQUIRE_ACCOUNT,
                    accountKeys(account),
                    Long.toString(now),
                    Long.toString(expiresAt),
                    Integer.toString(conversationProperties.maxConcurrentGlobal()),
                    Integer.toString(conversationProperties.maxConcurrentPerUser()),
                    owner,
                    Long.toString(Math.addExact(expiresAt, EXPIRY_GRACE_MILLIS)),
                    Short.toString(weight));
            Result mapped = switch (result == null ? -1 : result.intValue()) {
                case 1 -> Result.ACQUIRED;
                case 2 -> Result.GLOBAL_LIMIT_EXCEEDED;
                case 3 -> Result.ACCOUNT_LIMIT_EXCEEDED;
                default -> Result.INFRASTRUCTURE_UNAVAILABLE;
            };
            record(mapped);
            return mapped == Result.ACQUIRED
                    ? new AcquireResult(mapped, new AiInferenceConcurrencyPermit(
                    account, null, owner, weight))
                    : AcquireResult.rejected(mapped);
        } catch (RuntimeException exception) {
            record(Result.INFRASTRUCTURE_UNAVAILABLE);
            return AcquireResult.rejected(Result.INFRASTRUCTURE_UNAVAILABLE);
        }
    }

    @Override
    public AcquireResult tryAcquireApiKey(
            long loginIdentityId,
            String apiKeyDigestIdentifier,
            short weight) {
        requireWeight(weight);
        HmacIdentifier account = hasher.concurrencyUserIdentifier(loginIdentityId);
        HmacIdentifier apiKey = HmacIdentifier.fromProtectedValue(apiKeyDigestIdentifier);
        String owner = UUID.randomUUID().toString();
        long now = clock.millis();
        long expiresAt = Math.addExact(now, conversationProperties.inflightLeaseTtl().toMillis());
        try {
            Long result = redisTemplate.execute(
                    ACQUIRE_API_KEY,
                    apiKeyKeys(account, apiKey),
                    Long.toString(now),
                    Long.toString(expiresAt),
                    Integer.toString(apiKeyProperties.getMaxConcurrentPerKey()),
                    Integer.toString(conversationProperties.maxConcurrentPerUser()),
                    Integer.toString(conversationProperties.maxConcurrentGlobal()),
                    owner,
                    Long.toString(Math.addExact(expiresAt, EXPIRY_GRACE_MILLIS)),
                    Short.toString(weight));
            Result mapped = switch (result == null ? -1 : result.intValue()) {
                case 1 -> Result.ACQUIRED;
                case 2 -> Result.API_KEY_LIMIT_EXCEEDED;
                case 3 -> Result.ACCOUNT_LIMIT_EXCEEDED;
                case 4 -> Result.GLOBAL_LIMIT_EXCEEDED;
                default -> Result.INFRASTRUCTURE_UNAVAILABLE;
            };
            record(mapped);
            return mapped == Result.ACQUIRED
                    ? new AcquireResult(mapped, new AiInferenceConcurrencyPermit(
                    account, apiKey, owner, weight))
                    : AcquireResult.rejected(mapped);
        } catch (RuntimeException exception) {
            record(Result.INFRASTRUCTURE_UNAVAILABLE);
            return AcquireResult.rejected(Result.INFRASTRUCTURE_UNAVAILABLE);
        }
    }

    @Override
    public boolean renew(AiInferenceConcurrencyPermit permit) {
        long expiresAt = Math.addExact(
                clock.millis(), conversationProperties.inflightLeaseTtl().toMillis());
        try {
            Long result = redisTemplate.execute(
                    permit.includesApiKey() ? RENEW_API_KEY : RENEW_ACCOUNT,
                    permit.includesApiKey()
                            ? apiKeyKeys(permit.accountIdentifier(), permit.apiKeyIdentifier())
                            : accountKeys(permit.accountIdentifier()),
                    permit.owner(),
                    Short.toString(permit.weight()),
                    Long.toString(expiresAt),
                    Long.toString(Math.addExact(expiresAt, EXPIRY_GRACE_MILLIS)));
            boolean renewed = Long.valueOf(1L).equals(result);
            if (!renewed) {
                metrics.concurrency("renew_failed");
            }
            return renewed;
        } catch (RuntimeException exception) {
            metrics.concurrency("renew_failed");
            return false;
        }
    }

    @Override
    public void release(AiInferenceConcurrencyPermit permit) {
        try {
            Long result = redisTemplate.execute(
                    permit.includesApiKey() ? RELEASE_API_KEY : RELEASE_ACCOUNT,
                    permit.includesApiKey()
                            ? apiKeyKeys(permit.accountIdentifier(), permit.apiKeyIdentifier())
                            : accountKeys(permit.accountIdentifier()),
                    permit.owner(),
                    Short.toString(permit.weight()));
            metrics.concurrency(Long.valueOf(1L).equals(result)
                    ? "release" : "release_failed");
        } catch (RuntimeException exception) {
            metrics.concurrency("release_failed");
            // 释放失败由 ZSET score 和 Key 绝对过期时间收敛，不能覆盖已经确定的业务终态。
        }
    }

    private List<String> accountKeys(HmacIdentifier account) {
        return List.of(
                keyFactory.aiConversationGlobalConcurrencyKey(),
                keyFactory.aiConversationUserConcurrencyKey(account));
    }

    private List<String> apiKeyKeys(HmacIdentifier account, HmacIdentifier apiKey) {
        // 拒绝优先级由 Lua 的 Key、账号、全局检查顺序固定，物理账号和全局 Key 与旧 H5 链路保持一致。
        return List.of(
                keyFactory.aiInferenceApiKeyConcurrencyKey(apiKey),
                keyFactory.aiConversationUserConcurrencyKey(account),
                keyFactory.aiConversationGlobalConcurrencyKey());
    }

    private static void requireWeight(short weight) {
        if (weight < 1 || weight > 10) {
            throw new IllegalArgumentException("AI inference concurrency weight is invalid");
        }
    }

    private void record(Result result) {
        metrics.concurrency(result.name().toLowerCase());
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
