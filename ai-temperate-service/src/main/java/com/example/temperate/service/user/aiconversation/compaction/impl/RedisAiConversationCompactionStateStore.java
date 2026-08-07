package com.example.temperate.service.user.aiconversation.compaction.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.redis.key.ConversationRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionClaim;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionOperation;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionStateStore;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionStatus;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionTrigger;
import com.example.temperate.service.user.aiconversation.config.AiConversationContextUsageProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.context.event.AiConversationContextEventNotification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 使用短期 Redis Hash 维护压缩单飞状态，并用同一 Lua 内的长寿命计数 Key 生成事件 revision。
 *
 * <p>Pub/Sub 只发送小型唤醒通知；状态与终态始终以 Hash 快照为准。独立计数 Key 防止 Hash
 * 到期后 revision 回退，通知丢失仍由状态快照和重连恢复。</p>
 */
@Service
public final class RedisAiConversationCompactionStateStore
        implements AiConversationCompactionStateStore {

    private static final DefaultRedisScript<Long> CLAIM_SCRIPT = script(
            "lua/ai-conversation/claim_context_compaction.lua");
    private static final DefaultRedisScript<Long> TRANSITION_SCRIPT = script(
            "lua/ai-conversation/transition_context_compaction.lua");
    private static final DefaultRedisScript<Long> USAGE_SCRIPT = script(
            "lua/ai-conversation/publish_context_usage.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final HybridBase64UrlCodec idCodec;
    private final ObjectMapper objectMapper;
    private final AiConversationContextUsageProperties properties;
    private final AiConversationProperties conversationProperties;
    private final Clock clock;

    public RedisAiConversationCompactionStateStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            HybridBase64UrlCodec idCodec,
            ObjectMapper objectMapper,
            AiConversationContextUsageProperties properties,
            AiConversationProperties conversationProperties,
            Clock clock) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.idCodec = Objects.requireNonNull(idCodec);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.properties = Objects.requireNonNull(properties);
        this.conversationProperties = Objects.requireNonNull(
                conversationProperties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<AiConversationCompactionOperation> find(
            String conversationPublicId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(
                key(conversationPublicId));
        if (values.isEmpty()) {
            String eventRevision = redisTemplate.opsForValue().get(
                    eventRevisionKey(conversationPublicId));
            return eventRevision == null
                    ? Optional.empty()
                    : Optional.of(AiConversationCompactionOperation.idle(
                            Long.parseLong(eventRevision)));
        }
        return Optional.of(decode(values));
    }

    @Override
    public AiConversationCompactionClaim claim(
            String conversationPublicId,
            long contextRevision,
            AiConversationCompactionTrigger trigger) {
        String operationPublicId = newOperationPublicId();
        OffsetDateTime now = now();
        Long created = redisTemplate.execute(
                CLAIM_SCRIPT,
                List.of(
                        key(conversationPublicId),
                        eventRevisionKey(conversationPublicId)),
                operationPublicId,
                Long.toString(contextRevision),
                Objects.requireNonNull(trigger).name(),
                now.toString(),
                Long.toString(properties.operationTtl().toMillis()),
                Long.toString(conversationProperties.contextTtl().toMillis()));
        AiConversationCompactionOperation operation = find(conversationPublicId)
                .orElseThrow(() -> new IllegalStateException(
                        "AI context compaction state was not created."));
        boolean newClaim = Long.valueOf(1L).equals(created);
        if (newClaim) {
            publish(conversationPublicId, operation.eventRevision(),
                    "compaction_queued");
        }
        return new AiConversationCompactionClaim(operation, newClaim);
    }

    @Override
    public AiConversationCompactionOperation markRunning(
            String conversationPublicId,
            String operationPublicId) {
        return transition(
                conversationPublicId,
                operationPublicId,
                AiConversationCompactionStatus.RUNNING,
                "compaction_started",
                null,
                null,
                false,
                properties.operationTtl().toMillis());
    }

    @Override
    public AiConversationCompactionOperation markCompleted(
            String conversationPublicId,
            String operationPublicId,
            long contextRevision) {
        return transition(
                conversationPublicId,
                operationPublicId,
                AiConversationCompactionStatus.COMPLETED,
                "compaction_completed",
                contextRevision,
                null,
                false,
                properties.terminalRetention().toMillis());
    }

    @Override
    public AiConversationCompactionOperation markFailed(
            String conversationPublicId,
            String operationPublicId,
            String errorCode,
            boolean retryable) {
        return transition(
                conversationPublicId,
                operationPublicId,
                AiConversationCompactionStatus.FAILED,
                "compaction_failed",
                null,
                Objects.requireNonNull(errorCode),
                retryable,
                properties.terminalRetention().toMillis());
    }

    @Override
    public long publishUsage(
            String conversationPublicId,
            long contextRevision) {
        Long eventRevision = redisTemplate.execute(
                USAGE_SCRIPT,
                List.of(
                        key(conversationPublicId),
                        eventRevisionKey(conversationPublicId)),
                Long.toString(contextRevision),
                now().toString(),
                Long.toString(properties.operationTtl().toMillis()),
                Long.toString(conversationProperties.contextTtl().toMillis()));
        if (eventRevision == null || eventRevision <= 0L) {
            throw new IllegalStateException(
                    "AI context usage event revision was not created.");
        }
        publish(conversationPublicId, eventRevision, "context_usage");
        return eventRevision;
    }

    private AiConversationCompactionOperation transition(
            String conversationPublicId,
            String operationPublicId,
            AiConversationCompactionStatus status,
            String eventType,
            Long contextRevision,
            String errorCode,
            boolean retryable,
            long ttlMillis) {
        Long eventRevision = redisTemplate.execute(
                TRANSITION_SCRIPT,
                List.of(
                        key(conversationPublicId),
                        eventRevisionKey(conversationPublicId)),
                operationPublicId,
                status.name(),
                eventType,
                now().toString(),
                Long.toString(ttlMillis),
                contextRevision == null ? "" : Long.toString(contextRevision),
                Objects.requireNonNullElse(errorCode, ""),
                Boolean.toString(retryable),
                Long.toString(conversationProperties.contextTtl().toMillis()));
        if (eventRevision == null || eventRevision <= 0L) {
            throw new IllegalStateException(
                    "AI context compaction transition lost operation ownership.");
        }
        publish(conversationPublicId, eventRevision, eventType);
        return find(conversationPublicId).orElseThrow();
    }

    private AiConversationCompactionOperation decode(Map<Object, Object> values) {
        String statusValue = value(values, "status");
        AiConversationCompactionStatus status = statusValue == null
                ? AiConversationCompactionStatus.IDLE
                : AiConversationCompactionStatus.valueOf(statusValue);
        String triggerValue = value(values, "trigger");
        return new AiConversationCompactionOperation(
                blankToNull(value(values, "operationPublicId")),
                longValue(values, "contextRevision"),
                longValue(values, "eventRevision"),
                status,
                triggerValue == null || triggerValue.isBlank()
                        ? null : AiConversationCompactionTrigger.valueOf(triggerValue),
                dateTime(values, "createdAt"),
                dateTime(values, "updatedAt"),
                blankToNull(value(values, "errorCode")),
                Boolean.parseBoolean(value(values, "retryable")));
    }

    private void publish(
            String conversationPublicId,
            long eventRevision,
            String eventType) {
        try {
            redisTemplate.convertAndSend(
                    keyFactory.aiConversationContextEventsChannel(),
                    objectMapper.writeValueAsString(
                            new AiConversationContextEventNotification(
                                    AiConversationContextEventNotification
                                            .CURRENT_SCHEMA_VERSION,
                                    conversationPublicId,
                                    eventRevision,
                                    eventType)));
        } catch (JsonProcessingException | RuntimeException ignoredFailure) {
            // Pub/Sub 只负责唤醒；状态已经写入 Redis Hash，通知失败由快照和重连恢复。
        }
    }

    private String newOperationPublicId() {
        UUID uuid = UUID.randomUUID();
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return idCodec.encode(buffer.array());
    }

    private String key(String conversationPublicId) {
        return keyFactory.aiConversationCompactionStateKey(
                new ConversationRedisId(conversationPublicId));
    }

    private String eventRevisionKey(String conversationPublicId) {
        return keyFactory.aiConversationContextEventRevisionKey(
                new ConversationRedisId(conversationPublicId));
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private static String value(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        return value == null ? null : Objects.toString(value);
    }

    private static long longValue(Map<Object, Object> values, String field) {
        String value = value(values, field);
        return value == null || value.isBlank() ? 0L : Long.parseLong(value);
    }

    private static OffsetDateTime dateTime(
            Map<Object, Object> values,
            String field) {
        String value = value(values, field);
        return value == null || value.isBlank()
                ? null : OffsetDateTime.parse(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
