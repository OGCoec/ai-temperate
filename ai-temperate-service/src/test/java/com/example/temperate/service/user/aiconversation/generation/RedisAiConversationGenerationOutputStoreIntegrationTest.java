package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.redis.key.GenerationRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.generation.observer.impl.RedisAiConversationGenerationOutputStoreImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 使用隔离 Redis 验证后台回答分块 revision、快照顺序和终态恢复数据，不涉及资金状态。
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisAiConversationGenerationOutputStoreIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse(System.getenv().getOrDefault(
                    "AIT_TEST_REDIS_IMAGE", "redis:7.4.9-alpine")))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RedisAiConversationGenerationOutputStoreImpl store;
    private RedisKeyFactory keyFactory;
    private String generationPublicId;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        keyFactory = new RedisKeyFactory("test");
        store = new RedisAiConversationGenerationOutputStoreImpl(
                redisTemplate,
                keyFactory,
                properties(),
                new ObjectMapper().findAndRegisterModules());
        byte[] generationId = new byte[16];
        generationId[15] = 9;
        generationPublicId = new HybridBase64UrlCodec().encode(generationId);
    }

    @Test
    void appendsOrderedChunksAndRestoresTerminalSnapshot() {
        assertThat(store.appendDelta(generationPublicId, "第一段")).isEqualTo(1L);
        assertThat(store.appendDelta(generationPublicId, "第二段")).isEqualTo(2L);
        store.publishTerminal(
                generationPublicId,
                "completed",
                "{\"status\":\"SETTLED\"}");

        var snapshot = store.snapshot(generationPublicId);

        assertThat(snapshot.revision()).isEqualTo(2L);
        assertThat(snapshot.assistantText()).isEqualTo("第一段第二段");
        assertThat(snapshot.terminalEventName()).isEqualTo("completed");
        assertThat(snapshot.terminalDataJson()).contains("SETTLED");
        String snapshotKey = keyFactory.aiConversationGenerationSnapshotKey(
                new GenerationRedisId(generationPublicId));
        assertThat(redisTemplate.opsForHash().size(snapshotKey)).isEqualTo(5L);
    }

    private static AiConversationAsyncGenerationProperties properties() {
        return new AiConversationAsyncGenerationProperties(
                true,
                "instance-test",
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(24),
                2,
                Duration.ofMinutes(15));
    }
}
