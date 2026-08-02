package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import com.example.temperate.common.redis.key.GenerationRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamClientDiagnosticRateLimitService;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 使用 Redis 的 SET NX 加短 TTL 为每个 Generation 只接受一次浏览器汇总，
 * 使重复发送在多实例部署下仍然不会重复写入结构化诊断日志。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationStreamClientDiagnosticRateLimitServiceImpl
        implements AiConversationStreamClientDiagnosticRateLimitService {

    private static final Duration RETENTION = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public AiConversationStreamClientDiagnosticRateLimitServiceImpl(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public boolean tryAcquire(String generationPublicId) {
        String key = keyFactory.aiConversationGenerationBrowserDiagnosticKey(
                new GenerationRedisId(generationPublicId));
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key, "1", RETENTION);
        if (acquired == null) {
            throw new IllegalStateException(
                    "AI stream diagnostic rate limit did not return a result.");
        }
        return acquired;
    }
}
