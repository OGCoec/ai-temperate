package com.example.temperate.service.user.aiconversation.lease.impl;

import com.example.temperate.common.redis.key.ConversationRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLease;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLeaseService;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLeaseType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 使用 Redis SET NX 租约限制同一会话的生成并发和压缩并发，并通过 Lua 安全续租与释放。
 */
@Service
public final class RedisAiConversationLeaseService
        implements AiConversationLeaseService {

    private static final DefaultRedisScript<Long> RENEW_SCRIPT =
            script("lua/ai-conversation/renew_lease.lua");
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            script("lua/ai-conversation/release_lease.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final AiConversationProperties properties;

    public RedisAiConversationLeaseService(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            AiConversationProperties properties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public Optional<AiConversationLease> tryAcquire(
            String conversationPublicId, AiConversationLeaseType type) {
        String owner = UUID.randomUUID().toString();
        AiConversationLease lease =
                new AiConversationLease(conversationPublicId, type, owner);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key(lease), owner, ttl(type));
        return Boolean.TRUE.equals(acquired)
                ? Optional.of(lease)
                : Optional.empty();
    }

    @Override
    public boolean renew(AiConversationLease lease) {
        Duration ttl = ttl(lease.type());
        Long result = redisTemplate.execute(
                RENEW_SCRIPT,
                List.of(key(lease)),
                lease.owner(),
                Long.toString(ttl.toMillis()));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public void release(AiConversationLease lease) {
        redisTemplate.execute(
                RELEASE_SCRIPT,
                List.of(key(lease)),
                lease.owner());
    }

    private String key(AiConversationLease lease) {
        ConversationRedisId id =
                new ConversationRedisId(lease.conversationPublicId());
        return switch (lease.type()) {
            case INFLIGHT -> keyFactory.aiConversationInflightKey(id);
            case COMPACTION -> keyFactory.aiConversationCompactionKey(id);
        };
    }

    private Duration ttl(AiConversationLeaseType type) {
        return switch (type) {
            case INFLIGHT -> properties.inflightLeaseTtl();
            case COMPACTION -> properties.compactionLeaseTtl();
        };
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
