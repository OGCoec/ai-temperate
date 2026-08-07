package com.example.temperate.service.user.aiconversation.generation.observer.impl;

import com.example.temperate.common.redis.key.GenerationRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTransportDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.SystemAiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputEvent;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputSnapshot;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 使用分块 Hash 保存最多单个 Generation 的临时回答，并通过全局频道广播小型 revision 事件。
 *
 * <p>Redis 只承载可恢复快照和实时通知；RabbitMQ 与 PostgreSQL 终态不依赖 Pub/Sub 可靠性。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class RedisAiConversationGenerationOutputStoreImpl
        implements AiConversationGenerationOutputStore {

    private static final DefaultRedisScript<Long> APPEND_SCRIPT = script();

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final AiConversationAsyncGenerationProperties properties;
    private final ObjectMapper objectMapper;
    private final AiConversationStreamTransportDiagnosticService transportDiagnosticService;
    private final AiConversationStreamTimingClock timingClock;

    public RedisAiConversationGenerationOutputStoreImpl(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            AiConversationAsyncGenerationProperties properties,
            ObjectMapper objectMapper) {
        this(
                redisTemplate,
                keyFactory,
                properties,
                objectMapper,
                AiConversationStreamTransportDiagnosticService.noOp(),
                new SystemAiConversationStreamTimingClock());
    }

    @Autowired
    public RedisAiConversationGenerationOutputStoreImpl(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            AiConversationAsyncGenerationProperties properties,
            ObjectMapper objectMapper,
            AiConversationStreamTransportDiagnosticService transportDiagnosticService,
            AiConversationStreamTimingClock timingClock) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.properties = Objects.requireNonNull(properties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.transportDiagnosticService = Objects.requireNonNull(transportDiagnosticService);
        this.timingClock = Objects.requireNonNull(timingClock);
    }

    @Override
    public long appendDelta(String generationPublicId, String text) {
        return appendDelta(generationPublicId, text, null, Map.of());
    }

    @Override
    public long appendDelta(
            String generationPublicId,
            String text,
            AiConversationStreamTimingContext timingContext,
            Map<String, ?> diagnosticDetails) {
        if (text == null || text.isEmpty()) {
            return currentRevision(generationPublicId);
        }
        long appendStart = timingClock.nanoTime();
        Long revision;
        try {
            revision = redisTemplate.execute(
                    APPEND_SCRIPT,
                    java.util.List.of(key(generationPublicId)),
                    text,
                    Long.toString(properties.terminalRetention().toMillis()));
        } catch (RuntimeException failure) {
            recordFailure(
                    timingContext,
                    diagnosticDetails,
                    "REDIS_APPEND",
                    failure,
                    appendStart);
            throw failure;
        }
        if (revision == null || revision <= 0L) {
            throw new IllegalStateException("AI Generation output revision was not created.");
        }
        long appendEnd = timingClock.nanoTime();
        long publishStart = timingClock.nanoTime();
        try {
            publish(new AiConversationGenerationOutputEvent(
                    AiConversationGenerationOutputEvent.CURRENT_SCHEMA_VERSION,
                    generationPublicId,
                    revision,
                    "delta",
                    deltaDataJson(revision, text)));
        } catch (RuntimeException failure) {
            recordFailure(
                    timingContext,
                    diagnosticDetails,
                    "REDIS_PUBLISH",
                    failure,
                    publishStart);
            throw failure;
        }
        long publishEnd = timingClock.nanoTime();
        if (timingContext != null) {
            java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
            if (diagnosticDetails != null) {
                details.putAll(diagnosticDetails);
            }
            details.put("revision", revision);
            details.put("redisAppendElapsedMs", elapsedMillis(appendEnd - appendStart));
            details.put("redisPublishElapsedMs", elapsedMillis(publishEnd - publishStart));
            details.put("redisAppendStartElapsedMs", elapsedMillis(appendStart - timingContext.startedNanos()));
            details.put("redisAppendEndElapsedMs", elapsedMillis(appendEnd - timingContext.startedNanos()));
            details.put("redisPublishStartElapsedMs", elapsedMillis(publishStart - timingContext.startedNanos()));
            details.put("redisPublishEndElapsedMs", elapsedMillis(publishEnd - timingContext.startedNanos()));
            transportDiagnosticService.recordSafely(
                    timingContext,
                    "ai_stream_redis_delta_published",
                    details);
        }
        return revision;
    }

    private static long elapsedMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    private void recordFailure(
            AiConversationStreamTimingContext timingContext,
            Map<String, ?> diagnosticDetails,
            String failureStage,
            RuntimeException failure,
            long startedNanos) {
        if (timingContext == null) {
            return;
        }
        java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
        if (diagnosticDetails != null) {
            details.putAll(diagnosticDetails);
        }
        details.put("failureStage", failureStage);
        details.put("failureType", failure.getClass().getName());
        details.put("redisAppendElapsedMs", elapsedMillis(timingClock.nanoTime() - startedNanos));
        transportDiagnosticService.recordSafely(
                timingContext,
                "ai_stream_redis_delta_failed",
                details);
    }

    @Override
    public void publishTerminal(
            String generationPublicId,
            String eventName,
            String dataJson) {
        String key = key(generationPublicId);
        // 终态名称和数据必须通过同一条 HSET 同时可见，避免重连快照读到半个终态。
        redisTemplate.opsForHash().putAll(key, Map.of(
                "terminal:event", eventName,
                "terminal:data", dataJson));
        redisTemplate.expire(key, properties.terminalRetention());
        publish(new AiConversationGenerationOutputEvent(
                AiConversationGenerationOutputEvent.CURRENT_SCHEMA_VERSION,
                generationPublicId,
                currentRevision(generationPublicId),
                eventName,
                dataJson));
    }

    @Override
    public AiConversationGenerationOutputSnapshot snapshot(String generationPublicId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key(generationPublicId));
        long revision = parseRevision(values.get("revision"));
        String assistantText = values.entrySet().stream()
                .filter(entry -> Objects.toString(entry.getKey()).startsWith("delta:"))
                .sorted(java.util.Comparator.comparingLong(
                        entry -> deltaRevision(entry.getKey())))
                .map(entry -> Objects.toString(entry.getValue(), ""))
                .reduce("", String::concat);
        return new AiConversationGenerationOutputSnapshot(
                revision,
                assistantText,
                value(values, "terminal:event"),
                value(values, "terminal:data"));
    }

    private long currentRevision(String generationPublicId) {
        Object value = redisTemplate.opsForHash().get(key(generationPublicId), "revision");
        return parseRevision(value);
    }

    private void publish(AiConversationGenerationOutputEvent event) {
        redisTemplate.convertAndSend(
                keyFactory.aiConversationGenerationEventsChannel(), json(event));
    }

    private String deltaDataJson(long revision, String text) {
        // revision 必须先于可能很长的正文输出，使只读取有限元数据的诊断包装器仍能关联同一条 delta。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("revision", revision);
        data.put("type", "TEXT");
        data.put("text", text);
        return json(data);
    }

    private String key(String generationPublicId) {
        return keyFactory.aiConversationGenerationSnapshotKey(
                new GenerationRedisId(generationPublicId));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("AI Generation output serialization failed.", exception);
        }
    }

    private static long parseRevision(Object value) {
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(Objects.toString(value));
    }

    private static String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : Objects.toString(value);
    }

    private static long deltaRevision(Object field) {
        String value = Objects.toString(field);
        return Long.parseLong(value.substring("delta:".length()));
    }

    private static DefaultRedisScript<Long> script() {
        try {
            String source = new ClassPathResource(
                    "lua/ai-conversation/append-generation-output.lua")
                    .getContentAsString(StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(source, Long.class);
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
