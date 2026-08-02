package com.example.temperate.service.user.aiconversation.context.impl;

import com.example.temperate.common.redis.key.ConversationRedisBuildId;
import com.example.temperate.common.redis.key.ConversationRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextSnapshot;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextStore;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextWriteOutcome;
import com.example.temperate.service.user.aiconversation.context.AiConversationEphemeralStart;
import com.example.temperate.service.user.aiconversation.context.AiConversationTurn;
import com.example.temperate.service.user.aiconversation.context.AiConversationTurnState;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 使用 Redis Hash 保存有限的会话尾部、持久化摘要和可丢失的中断回答覆盖层。
 *
 * <p>创建脚本只在 Key 不存在时设置一次绝对 72 小时期限；所有追加和压缩脚本只校验
 * generation 并修改明确字段，绝不续期，也不会扫描未知 Key。</p>
 */
@Component
public final class RedisAiConversationContextStore
        implements AiConversationContextStore {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedisAiConversationContextStore.class);
    private static final int SCHEMA_VERSION = 1;
    private static final int FIELD_CHUNK_BYTES = 4 * 1024;
    // 参数体预留 RESP 编码、Lua SHA 和 Key 开销，确保完整 Redis 请求仍低于 1 MiB。
    private static final int MAX_COMMAND_BYTES = 880 * 1024;
    private static final String META_FIELD = "meta";
    private static final String DURABLE_COMPACT_FIELD = "compact:persistent";
    private static final String LEGACY_DURABLE_COMPACT_FIELD = "compact:durable";
    private static final String EPHEMERAL_COMPACT_FIELD = "compact:ephemeral";
    private static final DefaultRedisScript<Long> CREATE_SCRIPT =
            script("lua/ai-conversation/create_context.lua");
    private static final DefaultRedisScript<Long> CREATE_BUILD_SCRIPT =
            script("lua/ai-conversation/create_context_build.lua");
    private static final DefaultRedisScript<Long> APPEND_BUILD_SCRIPT =
            script("lua/ai-conversation/append_context_build.lua");
    private static final DefaultRedisScript<Long> PROMOTE_BUILD_SCRIPT =
            script("lua/ai-conversation/promote_context_build.lua");
    private static final DefaultRedisScript<Long> APPEND_SCRIPT =
            script("lua/ai-conversation/append_fields.lua");
    private static final DefaultRedisScript<Long> START_EPHEMERAL_SCRIPT =
            script("lua/ai-conversation/start_ephemeral.lua");
    private static final DefaultRedisScript<Long> MARK_INTERRUPTED_SCRIPT =
            script("lua/ai-conversation/mark_ephemeral_interrupted.lua");
    private static final DefaultRedisScript<Long> COMMIT_SCRIPT =
            script("lua/ai-conversation/commit_turn.lua");
    private static final DefaultRedisScript<Long> REPLACE_COMPACTION_SCRIPT =
            script("lua/ai-conversation/replace_compaction.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final ObjectMapper objectMapper;
    private final AiConversationProperties properties;
    private final Clock clock;
    private final AiConversationMetrics metrics;

    public RedisAiConversationContextStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            ObjectMapper objectMapper,
            AiConversationProperties properties,
            Clock clock,
            AiConversationMetrics metrics) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public Optional<AiConversationContextSnapshot> find(
            String conversationPublicId) {
        String key = key(conversationPublicId);
        Long fieldCount = redisTemplate.opsForHash().size(key);
        if (fieldCount != null && fieldCount > properties.maxHashFields()) {
            rejectOversizedContext(key, fieldCount);
            return Optional.empty();
        }
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        if (raw.size() > properties.maxHashFields()) {
            rejectOversizedContext(key, raw.size());
            return Optional.empty();
        }
        try {
            return Optional.of(decode(raw));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            redisTemplate.unlink(key);
            LOGGER.warn(
                    "event=ai_conversation_context_rejected reason=invalid_snapshot");
            metrics.context("damaged");
            return Optional.empty();
        }
    }

    @Override
    public void invalidate(String conversationPublicId) {
        redisTemplate.unlink(key(conversationPublicId));
    }

    @Override
    public AiConversationContextWriteOutcome create(
            String conversationPublicId,
            AiConversationContextSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        Map<String, String> fields = encodeSnapshot(snapshot);
        if (fields.size() + 1 > properties.maxHashFields()) {
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
        if (writeBytes(fields) > MAX_COMMAND_BYTES) {
            return createInBatches(conversationPublicId, snapshot, fields);
        }
        List<String> arguments = new ArrayList<>(2 + fields.size() * 2);
        arguments.add(snapshot.generation());
        arguments.add(Long.toString(snapshot.expiresAt().toInstant().toEpochMilli()));
        fields.forEach((field, value) -> {
            arguments.add(field);
            arguments.add(value);
        });
        try {
            Long result = redisTemplate.execute(
                    CREATE_SCRIPT,
                    List.of(key(conversationPublicId)),
                    arguments.toArray(String[]::new));
            return Long.valueOf(1L).equals(result)
                    ? AiConversationContextWriteOutcome.APPLIED
                    : AiConversationContextWriteOutcome.GENERATION_MISMATCH;
        } catch (RuntimeException failure) {
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
    }

    private AiConversationContextWriteOutcome createInBatches(
            String conversationPublicId,
            AiConversationContextSnapshot snapshot,
            Map<String, String> fields) {
        ConversationRedisId conversationId =
                new ConversationRedisId(conversationPublicId);
        ConversationRedisBuildId buildId = new ConversationRedisBuildId(
                UUID.randomUUID().toString().replace("-", ""));
        String buildKey = keyFactory.aiConversationContextBuildKey(
                conversationId, buildId);
        String finalKey = key(conversationPublicId);
        long buildExpiresAt = Math.addExact(
                clock.millis(), java.time.Duration.ofMinutes(5).toMillis());
        try {
            Long created = redisTemplate.execute(
                    CREATE_BUILD_SCRIPT,
                    List.of(buildKey),
                    snapshot.generation(),
                    Long.toString(buildExpiresAt));
            if (!Long.valueOf(1L).equals(created)) {
                return AiConversationContextWriteOutcome.UNAVAILABLE;
            }
            for (Map<String, String> batch : writeBatches(fields)) {
                List<String> arguments = new ArrayList<>(2 + batch.size() * 2);
                arguments.add(snapshot.generation());
                arguments.add(Integer.toString(properties.maxHashFields()));
                batch.forEach((field, value) -> {
                    arguments.add(field);
                    arguments.add(value);
                });
                Long appended = redisTemplate.execute(
                        APPEND_BUILD_SCRIPT,
                        List.of(buildKey),
                        arguments.toArray(String[]::new));
                if (!Long.valueOf(1L).equals(appended)) {
                    redisTemplate.unlink(buildKey);
                    return AiConversationContextWriteOutcome.UNAVAILABLE;
                }
            }
            Long promoted = redisTemplate.execute(
                    PROMOTE_BUILD_SCRIPT,
                    List.of(buildKey, finalKey),
                    snapshot.generation(),
                    Long.toString(snapshot.expiresAt()
                            .toInstant().toEpochMilli()),
                    Integer.toString(properties.maxHashFields()));
            if (Long.valueOf(1L).equals(promoted)) {
                return AiConversationContextWriteOutcome.APPLIED;
            }
            redisTemplate.unlink(buildKey);
            return Long.valueOf(0L).equals(promoted)
                    ? AiConversationContextWriteOutcome.GENERATION_MISMATCH
                    : AiConversationContextWriteOutcome.UNAVAILABLE;
        } catch (RuntimeException failure) {
            try {
                redisTemplate.unlink(buildKey);
            } catch (RuntimeException ignoredFailure) {
                // 临时 Key 已有五分钟绝对期限，清理失败不会形成长期遗留数据。
            }
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
    }

    private static List<Map<String, String>> writeBatches(
            Map<String, String> fields) {
        List<Map<String, String>> batches = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();
        int bytes = 0;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            int entryBytes = Math.addExact(
                    entry.getKey().getBytes(StandardCharsets.UTF_8).length,
                    entry.getValue().getBytes(StandardCharsets.UTF_8).length);
            if (!current.isEmpty()
                    && Math.addExact(bytes, entryBytes) > MAX_COMMAND_BYTES) {
                batches.add(Map.copyOf(current));
                current.clear();
                bytes = 0;
            }
            current.put(entry.getKey(), entry.getValue());
            bytes = Math.addExact(bytes, entryBytes);
        }
        if (!current.isEmpty()) {
            batches.add(Map.copyOf(current));
        }
        return List.copyOf(batches);
    }

    @Override
    public AiConversationEphemeralStart appendEphemeralUser(
            String conversationPublicId,
            String generation,
            String usagePublicId,
            AiConversationContent user) {
        Map<String, String> fields = new LinkedHashMap<>();
        putChunked(fields, "user", json(user));
        if (!writeWithinLimits(fields, 2)) {
            return AiConversationEphemeralStart.failed(
                    AiConversationContextWriteOutcome.UNAVAILABLE);
        }
        List<String> arguments = new ArrayList<>(5 + fields.size() * 2);
        arguments.add(generation);
        arguments.add(usagePublicId);
        arguments.add(clock.instant().atOffset(ZoneOffset.UTC).toString());
        arguments.add(Integer.toString(fields.size()));
        arguments.add(Integer.toString(properties.maxHashFields()));
        fields.forEach((field, value) -> {
            arguments.add(field);
            arguments.add(value);
        });
        try {
            Long result = redisTemplate.execute(
                    START_EPHEMERAL_SCRIPT,
                    List.of(key(conversationPublicId)),
                    arguments.toArray(String[]::new));
            if (result != null && result > 0L) {
                return AiConversationEphemeralStart.applied(result);
            }
            return AiConversationEphemeralStart.failed(
                    Long.valueOf(0L).equals(result)
                            ? AiConversationContextWriteOutcome.GENERATION_MISMATCH
                            : AiConversationContextWriteOutcome.UNAVAILABLE);
        } catch (RuntimeException failure) {
            return AiConversationEphemeralStart.failed(
                    AiConversationContextWriteOutcome.UNAVAILABLE);
        }
    }

    @Override
    public AiConversationContextWriteOutcome appendAssistantChunks(
            String conversationPublicId,
            String generation,
            long ephemeralOrdinal,
            int firstChunkNumber,
            List<String> chunks) {
        if (firstChunkNumber < 0 || chunks == null || chunks.isEmpty()) {
            return AiConversationContextWriteOutcome.APPLIED;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        int number = firstChunkNumber;
        for (String chunk : chunks) {
            fields.put(
                    "ephemeral:" + ephemeralOrdinal + ":assistant:"
                            + String.format("%08d", number++),
                    Objects.requireNonNull(chunk));
        }
        return append(conversationPublicId, generation, fields);
    }

    @Override
    public AiConversationContextWriteOutcome markEphemeralInterrupted(
            String conversationPublicId,
            String generation,
            long ephemeralOrdinal) {
        try {
            Long result = redisTemplate.execute(
                    MARK_INTERRUPTED_SCRIPT,
                    List.of(key(conversationPublicId)),
                    generation,
                    Long.toString(ephemeralOrdinal));
            return Long.valueOf(1L).equals(result)
                    ? AiConversationContextWriteOutcome.APPLIED
                    : Long.valueOf(0L).equals(result)
                            ? AiConversationContextWriteOutcome.GENERATION_MISMATCH
                            : AiConversationContextWriteOutcome.UNAVAILABLE;
        } catch (RuntimeException failure) {
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
    }

    @Override
    public AiConversationContextWriteOutcome commitPersistedTurn(
            String conversationPublicId,
            String generation,
            long messageId,
            long ephemeralOrdinal,
            AiConversationContent user,
            AiConversationContent assistant) {
        AiConversationContextSnapshot current = find(conversationPublicId)
                .filter(snapshot -> snapshot.generation().equals(generation))
                .orElse(null);
        if (current == null) {
            return AiConversationContextWriteOutcome.GENERATION_MISMATCH;
        }
        Map<String, String> writes = new LinkedHashMap<>();
        putChunked(writes, "persistent:" + messageId + ":user", json(user));
        putChunked(writes, "persistent:" + messageId + ":assistant", json(assistant));
        CacheMeta meta = new CacheMeta(
                SCHEMA_VERSION,
                generation,
                current.createdAt(),
                current.expiresAt(),
                current.lastCompactedMessageId(),
                Math.max(current.latestPersistedMessageId(), messageId));
        List<String> deletes = fieldsWithPrefix(
                conversationPublicId, "ephemeral:" + ephemeralOrdinal + ":");
        if (!writeWithinLimits(writes, 0)
                || current.fieldCount() - deletes.size() + writes.size()
                > properties.maxHashFields()
                || deletes.size() > properties.maxHashFields()) {
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
        List<String> arguments =
                new ArrayList<>(4 + writes.size() * 2 + deletes.size());
        arguments.add(generation);
        arguments.add(json(meta));
        arguments.add(Integer.toString(properties.maxHashFields()));
        arguments.add(Integer.toString(writes.size()));
        writes.forEach((field, value) -> {
            arguments.add(field);
            arguments.add(value);
        });
        arguments.addAll(deletes);
        if (writeBytes(arguments) > MAX_COMMAND_BYTES) {
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
        try {
            Long result = redisTemplate.execute(
                    COMMIT_SCRIPT,
                    List.of(key(conversationPublicId)),
                    arguments.toArray(String[]::new));
            return outcome(result);
        } catch (RuntimeException failure) {
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
    }

    @Override
    public AiConversationContextWriteOutcome replaceDurableCompaction(
            String conversationPublicId,
            String generation,
            long cutoffMessageId,
            String compactedContextJson) {
        AiConversationContextSnapshot current = find(conversationPublicId)
                .filter(snapshot -> snapshot.generation().equals(generation))
                .orElse(null);
        if (current == null) {
            return AiConversationContextWriteOutcome.GENERATION_MISMATCH;
        }
        List<String> deletes = fieldsWithPrefix(
                conversationPublicId, "persistent:")
                .stream()
                .filter(field -> durableMessageId(field) <= cutoffMessageId)
                .toList();
        if (deletes.size() > properties.maxHashFields()) {
            LOGGER.warn(
                    "event=ai_conversation_compaction_cache_skipped reason=delete_limit fields={}",
                    deletes.size());
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
        CacheMeta meta = new CacheMeta(
                SCHEMA_VERSION,
                generation,
                current.createdAt(),
                current.expiresAt(),
                cutoffMessageId,
                current.latestPersistedMessageId());
        List<String> compactionFields = new ArrayList<>(fieldsWithPrefix(
                conversationPublicId, DURABLE_COMPACT_FIELD));
        compactionFields.addAll(fieldsWithPrefix(
                conversationPublicId, LEGACY_DURABLE_COMPACT_FIELD));
        return replaceCompaction(
                conversationPublicId,
                generation,
                DURABLE_COMPACT_FIELD,
                compactedContextJson,
                meta,
                current.fieldCount(),
                mergeDistinct(compactionFields, deletes));
    }

    @Override
    public AiConversationContextWriteOutcome replaceEphemeralCompaction(
            String conversationPublicId,
            String generation,
            String compactedContextJson,
            long throughEphemeralOrdinal,
            List<Long> compactedEphemeralOrdinals) {
        AiConversationContextSnapshot current = find(conversationPublicId)
                .filter(snapshot -> snapshot.generation().equals(generation))
                .orElse(null);
        if (current == null) {
            return AiConversationContextWriteOutcome.GENERATION_MISMATCH;
        }
        // cutoff 只描述摘要覆盖到哪里；实际删除必须限定为本次选中的 INTERRUPTED 轮次，
        // 否则较早但仍在 STREAMING 的请求会被并发压缩误删。
        Set<Long> selectedOrdinals = Set.copyOf(compactedEphemeralOrdinals);
        List<String> deletes = fieldsWithPrefix(
                conversationPublicId, "ephemeral:").stream()
                .filter(field -> selectedOrdinals.contains(ephemeralOrdinal(field)))
                .limit(properties.maxHashFields() + 1L)
                .toList();
        if (deletes.size() > properties.maxHashFields()) {
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
        CacheMeta meta = new CacheMeta(
                SCHEMA_VERSION,
                generation,
                current.createdAt(),
                current.expiresAt(),
                current.lastCompactedMessageId(),
                current.latestPersistedMessageId());
        List<String> compactionFields = fieldsWithPrefix(
                conversationPublicId, EPHEMERAL_COMPACT_FIELD);
        return replaceCompaction(
                conversationPublicId,
                generation,
                EPHEMERAL_COMPACT_FIELD,
                compactedContextJson,
                meta,
                current.fieldCount(),
                mergeDistinct(compactionFields, deletes));
    }

    private AiConversationContextWriteOutcome append(
            String conversationPublicId,
            String generation,
            Map<String, String> fields) {
        if (!writeWithinLimits(fields, 0)) {
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
        List<String> arguments = new ArrayList<>(2 + fields.size() * 2);
        arguments.add(generation);
        arguments.add(Integer.toString(properties.maxHashFields()));
        fields.forEach((field, value) -> {
            arguments.add(field);
            arguments.add(value);
        });
        try {
            Long result = redisTemplate.execute(
                    APPEND_SCRIPT,
                    List.of(key(conversationPublicId)),
                    arguments.toArray(String[]::new));
            return outcome(result);
        } catch (RuntimeException failure) {
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
    }

    private AiConversationContextWriteOutcome replaceCompaction(
            String conversationPublicId,
            String generation,
            String compactField,
            String compactedContextJson,
            CacheMeta meta,
            int currentFieldCount,
            List<String> deletes) {
        Map<String, String> writes = new LinkedHashMap<>();
        putCompactionChunked(
                writes,
                compactField,
                Objects.requireNonNull(compactedContextJson));
        if (currentFieldCount - deletes.size() + writes.size()
                > properties.maxHashFields()) {
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
        List<String> arguments = new ArrayList<>(
                5 + deletes.size() + writes.size() * 2);
        arguments.add(generation);
        arguments.add(json(meta));
        arguments.add(Integer.toString(properties.maxHashFields()));
        arguments.add(Integer.toString(deletes.size()));
        arguments.addAll(deletes);
        arguments.add(Integer.toString(writes.size()));
        writes.forEach((field, value) -> {
            arguments.add(field);
            arguments.add(value);
        });
        if (writeBytes(arguments) > MAX_COMMAND_BYTES) {
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
        try {
            Long result = redisTemplate.execute(
                    REPLACE_COMPACTION_SCRIPT,
                    List.of(key(conversationPublicId)),
                    arguments.toArray(String[]::new));
            return outcome(result);
        } catch (RuntimeException failure) {
            return AiConversationContextWriteOutcome.UNAVAILABLE;
        }
    }

    private Map<String, String> encodeSnapshot(
            AiConversationContextSnapshot snapshot) {
        Map<String, String> fields = new LinkedHashMap<>();
        CacheMeta meta = new CacheMeta(
                SCHEMA_VERSION,
                snapshot.generation(),
                snapshot.createdAt(),
                snapshot.expiresAt(),
                snapshot.lastCompactedMessageId(),
                snapshot.latestPersistedMessageId());
        fields.put(META_FIELD, json(meta));
        if (snapshot.durableCompactionJson() != null) {
            putCompactionChunked(
                    fields,
                    DURABLE_COMPACT_FIELD,
                    snapshot.durableCompactionJson());
        }
        if (snapshot.ephemeralCompactionJson() != null) {
            putCompactionChunked(
                    fields,
                    EPHEMERAL_COMPACT_FIELD,
                    snapshot.ephemeralCompactionJson());
        }
        for (AiConversationTurn turn : snapshot.turns()) {
            String root = turn.ephemeral()
                    ? "ephemeral:" + turn.ordinal()
                    : "persistent:" + turn.messageId();
            putChunked(fields, root + ":user", json(turn.user()));
            putChunked(fields, root + ":assistant", json(turn.assistant()));
            if (turn.ephemeral()) {
                fields.put(
                        root + ":meta",
                        json(new EphemeralMeta(
                                SCHEMA_VERSION,
                                turn.state().name(),
                                turn.ordinal(),
                                turn.reference(),
                                snapshot.createdAt())));
            }
        }
        return fields;
    }

    private AiConversationContextSnapshot decode(Map<Object, Object> raw)
            throws JsonProcessingException {
        Map<String, String> fields = new HashMap<>();
        raw.forEach((field, value) ->
                fields.put(Objects.toString(field), Objects.toString(value)));
        CacheMeta meta = objectMapper.readValue(fields.get(META_FIELD), CacheMeta.class);
        if (meta.schemaVersion() != SCHEMA_VERSION
                || !Objects.equals(meta.generation(), fields.get("generation"))) {
            throw new IllegalArgumentException("Unsupported AI conversation cache schema.");
        }

        Map<String, TurnParts> turns = new HashMap<>();
        fields.forEach((field, value) -> collectTurnPart(turns, field, value));
        List<AiConversationTurn> decodedTurns = turns.entrySet().stream()
                .map(entry -> decodeTurn(entry.getKey(), entry.getValue()))
                .filter(turn -> turn.state()
                        != AiConversationTurnState.STREAMING)
                .sorted(Comparator
                        .comparing(AiConversationTurn::ephemeral)
                        .thenComparing(turn -> turn.ephemeral()
                                ? turn.ordinal()
                                : turn.messageId()))
                .toList();
        return new AiConversationContextSnapshot(
                meta.schemaVersion(),
                meta.generation(),
                meta.createdAt(),
                meta.expiresAt(),
                meta.lastCompactedMessageId(),
                meta.latestPersistedMessageId(),
                compactionValue(
                        fields,
                        DURABLE_COMPACT_FIELD,
                        LEGACY_DURABLE_COMPACT_FIELD),
                compactionValue(fields, EPHEMERAL_COMPACT_FIELD, null),
                decodedTurns,
                raw.size());
    }

    private void collectTurnPart(
            Map<String, TurnParts> turns, String field, String value) {
        if (!field.startsWith("turn:")
                && !field.startsWith("persistent:")
                && !field.startsWith("ephemeral:")) {
            return;
        }
        String[] segments = field.split(":");
        if (segments.length < 3) {
            return;
        }
        String reference = segments[0] + ":" + segments[1];
        TurnParts parts = turns.computeIfAbsent(reference, ignored -> new TurnParts());
        if ("meta".equals(segments[2])) {
            parts.meta = value;
            return;
        }
        if (segments.length < 4) {
            return;
        }
        if ("user".equals(segments[2])) {
            parts.user.put(segments[3], value);
        } else if ("assistant".equals(segments[2])) {
            parts.assistant.put(segments[3], value);
        }
    }

    private AiConversationTurn decodeTurn(String reference, TurnParts parts) {
        boolean ephemeral = reference.startsWith("ephemeral:");
        String id = reference.substring(reference.indexOf(':') + 1);
        String userJson = join(parts.user);
        String assistantValue = join(parts.assistant);
        try {
            AiConversationContent user =
                    objectMapper.readValue(userJson, AiConversationContent.class);
            AiConversationContent assistant;
            try {
                assistant = objectMapper.readValue(
                        assistantValue, AiConversationContent.class);
            } catch (JsonProcessingException exception) {
                // 流式草稿按原始文本分块写入，完整轮次才会改写为结构化内容。
                assistant = new AiConversationContent(assistantValue, List.of());
            }
            if (!ephemeral) {
                return new AiConversationTurn(
                        id,
                        Long.parseLong(id),
                        null,
                        user,
                        assistant,
                        AiConversationTurnState.PERSISTED);
            }
            EphemeralMeta meta = objectMapper.readValue(
                    parts.meta, EphemeralMeta.class);
            return new AiConversationTurn(
                    meta.usagePublicId(),
                    null,
                    meta.ordinal(),
                    user,
                    assistant,
                    AiConversationTurnState.valueOf(meta.state()));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "AI conversation turn cache is invalid.", exception);
        }
    }

    private List<String> fieldsWithPrefix(
            String conversationPublicId, String prefix) {
        return redisTemplate.opsForHash().keys(key(conversationPublicId)).stream()
                .map(Objects::toString)
                .filter(field -> field.startsWith(prefix))
                .sorted()
                .toList();
    }

    private static long durableMessageId(String field) {
        String[] segments = field.split(":");
        return Long.parseLong(segments[1]);
    }

    private static long ephemeralOrdinal(String field) {
        String[] segments = field.split(":");
        return Long.parseLong(segments[1]);
    }

    private static String join(Map<String, String> chunks) {
        return chunks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .reduce("", String::concat);
    }

    private void putChunked(
            Map<String, String> target, String prefix, String value) {
        List<String> chunks = utf8Chunks(value, FIELD_CHUNK_BYTES);
        for (int index = 0; index < chunks.size(); index++) {
            target.put(prefix + ":" + String.format("%08d", index), chunks.get(index));
        }
    }

    private void putCompactionChunked(
            Map<String, String> target, String prefix, String value) {
        List<String> chunks = utf8Chunks(value, FIELD_CHUNK_BYTES);
        for (int index = 0; index < chunks.size(); index++) {
            String field = index == 0
                    ? prefix
                    : prefix + ":" + String.format("%08d", index);
            target.put(field, chunks.get(index));
        }
    }

    private static String compactionValue(
            Map<String, String> fields,
            String prefix,
            String legacyPrefix) {
        String value = joinCompactionChunks(fields, prefix);
        if (value != null || legacyPrefix == null) {
            return value;
        }
        return joinCompactionChunks(fields, legacyPrefix);
    }

    private static String joinCompactionChunks(
            Map<String, String> fields, String prefix) {
        String root = fields.get(prefix);
        List<Map.Entry<String, String>> suffixes = fields.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix + ":"))
                .sorted(Map.Entry.comparingByKey())
                .toList();
        if (root == null && suffixes.isEmpty()) {
            return null;
        }
        StringBuilder joined = new StringBuilder(
                root == null ? "" : root);
        suffixes.forEach(entry -> joined.append(entry.getValue()));
        return joined.toString();
    }

    private static List<String> mergeDistinct(
            List<String> first, List<String> second) {
        LinkedHashSet<String> fields = new LinkedHashSet<>(first);
        fields.addAll(second);
        return List.copyOf(fields);
    }

    private void rejectOversizedContext(String key, long fieldCount) {
        // 先用 HLEN 阻止超限 Hash 进入 HGETALL；并发增长后的二次检查仍走同一可恢复清理路径。
        redisTemplate.unlink(key);
        LOGGER.warn(
                "event=ai_conversation_context_rejected reason=field_limit fields={}",
                fieldCount);
        metrics.context("damaged");
    }

    private boolean writeWithinLimits(
            Map<String, String> fields,
            int additionalFields) {
        return fields.size() + additionalFields <= properties.maxHashFields()
                && writeBytes(fields) <= MAX_COMMAND_BYTES;
    }

    private static int writeBytes(Map<String, String> fields) {
        int bytes = 0;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            bytes = Math.addExact(
                    bytes,
                    entry.getKey().getBytes(StandardCharsets.UTF_8).length);
            bytes = Math.addExact(
                    bytes,
                    entry.getValue().getBytes(StandardCharsets.UTF_8).length);
        }
        return bytes;
    }

    private static int writeBytes(List<String> values) {
        int bytes = 0;
        for (String value : values) {
            bytes = Math.addExact(
                    bytes,
                    value.getBytes(StandardCharsets.UTF_8).length);
        }
        return bytes;
    }

    private static AiConversationContextWriteOutcome outcome(Long result) {
        if (Long.valueOf(1L).equals(result)) {
            return AiConversationContextWriteOutcome.APPLIED;
        }
        if (Long.valueOf(0L).equals(result)) {
            return AiConversationContextWriteOutcome.GENERATION_MISMATCH;
        }
        return AiConversationContextWriteOutcome.UNAVAILABLE;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "AI conversation cache serialization failed.", exception);
        }
    }

    private String key(String publicId) {
        return keyFactory.aiConversationContextKey(
                new ConversationRedisId(publicId));
    }

    private static List<String> utf8Chunks(String value, int maxBytes) {
        if (value == null || value.isEmpty()) {
            return List.of("");
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bytes = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            String unit = new String(Character.toChars(codePoint));
            int unitBytes = unit.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 0 && bytes + unitBytes > maxBytes) {
                chunks.add(current.toString());
                current.setLength(0);
                bytes = 0;
            }
            current.append(unit);
            bytes += unitBytes;
            index += Character.charCount(codePoint);
        }
        chunks.add(current.toString());
        return List.copyOf(chunks);
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

    private record CacheMeta(
            int schemaVersion,
            String generation,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            long lastCompactedMessageId,
            long latestPersistedMessageId) {

        private CacheMeta {
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    private record EphemeralMeta(
            int schemaVersion,
            String state,
            long ordinal,
            String usagePublicId,
            OffsetDateTime createdAt) {
    }

    private static final class TurnParts {
        private final Map<String, String> user = new HashMap<>();
        private final Map<String, String> assistant = new HashMap<>();
        private String meta;
    }
}
