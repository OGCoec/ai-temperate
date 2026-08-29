package com.example.temperate.service.user.aiconversation.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 该测试是来验证 AI 上下文分块 Lua 在最大字段量、冲突和覆盖写入下仍保持原子字段数与绝对 TTL 契约。
 */
@Testcontainers(disabledWithoutDocker = true)
class AiConversationLuaChunkingIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.9-alpine"))
                    .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void fiveThousandFieldsAreChunkedAndOverwriteDoesNotConsumeCapacityOrExtendTtl()
            throws Exception {
        String key = "ait:test:ai:context:v2:test-five-thousand";
        long expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2);
        Long created = redisTemplate.execute(
                script("create_context.lua"),
                List.of(key),
                arguments("generation-a", Long.toString(expiresAt), Map.of("meta", "{}")));
        assertThat(created).isEqualTo(1L);
        Long ttlBefore = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);

        List<String> append = new ArrayList<>(2 + 5_000 * 2);
        append.add("generation-a");
        append.add("5002");
        for (int index = 0; index < 5_000; index++) {
            append.add("field:" + String.format("%04d", index));
            append.add("value-" + index);
        }
        Long first = redisTemplate.execute(
                script("append_fields.lua"), List.of(key), append.toArray());
        Long overwrite = redisTemplate.execute(
                script("append_fields.lua"), List.of(key), append.toArray());
        Long ttlAfter = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);

        assertThat(first).isEqualTo(1L);
        assertThat(overwrite).isEqualTo(1L);
        assertThat(redisTemplate.opsForHash().size(key)).isEqualTo(5_002L);
        assertThat(ttlAfter).isLessThanOrEqualTo(ttlBefore);
    }

    @Test
    void revisionAndGenerationConflictsLeaveTheHashUnchanged() throws Exception {
        String key = "ait:test:ai:context:v2:test-conflict";
        redisTemplate.opsForHash().putAll(key, Map.of(
                "generation", "generation-a",
                "meta", "{\"contextRevision\":7}",
                "old", "preserved"));
        Map<Object, Object> before = new LinkedHashMap<>(
                redisTemplate.opsForHash().entries(key));
        Object[] mutation = {
            "generation-a", "6", "{\"contextRevision\":8}", "10", "1",
            "new", "value", "old"
        };

        Long revisionConflict = redisTemplate.execute(
                script("commit_turn.lua"), List.of(key), mutation);
        mutation[0] = "generation-b";
        Long generationConflict = redisTemplate.execute(
                script("commit_turn.lua"), List.of(key), mutation);
        Long fieldLimit = redisTemplate.execute(
                script("commit_turn.lua"),
                List.of(key),
                "generation-a", "7", "{\"contextRevision\":8}", "3", "1",
                "new", "value");

        assertThat(revisionConflict).isEqualTo(2L);
        assertThat(generationConflict).isZero();
        assertThat(fieldLimit).isEqualTo(-1L);
        assertThat(redisTemplate.opsForHash().entries(key)).isEqualTo(before);
    }

    @Test
    void interruptedAnswerUsesTheOldAndNewChunkUnionForFinalFieldCount()
            throws Exception {
        String key = "ait:test:ai:context:v2:test-interrupted";
        redisTemplate.opsForHash().putAll(key, Map.of(
                "generation", "generation-a",
                "meta", "{\"estimatedContextTokens\":0,\"contextRevision\":1}",
                "ephemeral:1:meta",
                "{\"assistantChunkCount\":3,\"estimatedTokens\":0}",
                "ephemeral:1:assistant:00000000", "old-0",
                "ephemeral:1:assistant:00000001", "old-1",
                "ephemeral:1:assistant:00000002", "old-2"));

        Long result = redisTemplate.execute(
                script("save_ephemeral_interrupted.lua"),
                List.of(key),
                "generation-a", "1", "USER_STOP", "4",
                "2026-08-24T12:00:00Z", "2", "6", "new-0", "new-1");

        assertThat(result).isEqualTo(1L);
        assertThat(redisTemplate.opsForHash().get(
                key, "ephemeral:1:assistant:00000000")).isEqualTo("new-0");
        assertThat(redisTemplate.opsForHash().get(
                key, "ephemeral:1:assistant:00000002")).isNull();
        assertThat(redisTemplate.opsForHash().size(key)).isEqualTo(5L);
    }

    @Test
    void compactionOverlapIsCountedByItsFinalWrittenState() throws Exception {
        String key = "ait:test:ai:context:v2:test-compaction";
        redisTemplate.opsForHash().putAll(key, Map.of(
                "generation", "generation-a",
                "meta", "{\"contextRevision\":1}",
                "compact:persistent", "old"));

        Long result = redisTemplate.execute(
                script("replace_compaction.lua"),
                List.of(key),
                "generation-a", "1", "{\"contextRevision\":2}", "3",
                "1", "compact:persistent",
                "1", "compact:persistent", "new");

        assertThat(result).isEqualTo(1L);
        assertThat(redisTemplate.opsForHash().size(key)).isEqualTo(3L);
        assertThat(redisTemplate.opsForHash().get(key, "compact:persistent"))
                .isEqualTo("new");
    }

    @Test
    void buildHashRemainsInvisibleUntilAtomicPromotion() throws Exception {
        String buildKey = "ait:test:ai:context-build:v2:test-promote";
        String finalKey = "ait:test:ai:context:v2:test-promote";
        String expiresAt = Long.toString(
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2));

        assertThat(redisTemplate.execute(
                        script("create_context_build.lua"),
                        List.of(buildKey),
                        "generation-a",
                        expiresAt))
                .isEqualTo(1L);
        assertThat(redisTemplate.execute(
                        script("append_context_build.lua"),
                        List.of(buildKey),
                        "generation-a",
                        "10",
                        "meta",
                        "{\"contextRevision\":0}",
                        "field:0001",
                        "value-1"))
                .isEqualTo(1L);
        assertThat(redisTemplate.hasKey(buildKey)).isTrue();
        assertThat(redisTemplate.hasKey(finalKey)).isFalse();

        assertThat(redisTemplate.execute(
                        script("promote_context_build.lua"),
                        List.of(buildKey, finalKey),
                        "generation-a",
                        expiresAt,
                        "10"))
                .isEqualTo(1L);
        assertThat(redisTemplate.hasKey(buildKey)).isFalse();
        assertThat(redisTemplate.opsForHash().entries(finalKey))
                .containsEntry("generation", "generation-a")
                .containsEntry("meta", "{\"contextRevision\":0}")
                .containsEntry("field:0001", "value-1");
    }

    private static Object[] arguments(
            String generation,
            String expiresAt,
            Map<String, String> fields) {
        List<String> arguments = new ArrayList<>(2 + fields.size() * 2);
        arguments.add(generation);
        arguments.add(expiresAt);
        fields.forEach((field, value) -> {
            arguments.add(field);
            arguments.add(value);
        });
        return arguments.toArray();
    }

    private static DefaultRedisScript<Long> script(String name) throws Exception {
        String source = new ClassPathResource("lua/ai-conversation/" + name)
                .getContentAsString(StandardCharsets.UTF_8);
        return new DefaultRedisScript<>(source, Long.class);
    }
}
