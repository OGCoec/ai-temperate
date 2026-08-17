package com.example.temperate.service.bloom.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.bloom.counting.CountingBloomLayout;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.bloom.CountingBloomEngine.BuildFence;
import com.example.temperate.service.bloom.CountingBloomNamespace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
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
 * 该测试是来通过真实 Redis 验证计数 Bloom 构建参数可以经过 StringRedisTemplate
 * 完成序列化，并覆盖 Bucket 初始化与摘要位置写入边界。
 */
@Testcontainers(disabledWithoutDocker = true)
final class RedisCountingBloomEngineImplIntegrationTest {

    private static final String REDIS_IMAGE =
            System.getenv().getOrDefault("AIT_TEST_REDIS_IMAGE", "redis:7.4.9-alpine");
    private static final RedisKeyFactory KEY_FACTORY = new RedisKeyFactory("test");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private SimpleMeterRegistry meterRegistry;
    private RedisCountingBloomEngineImpl engine;

    @BeforeAll
    static void connectToRedis() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectFromRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        meterRegistry = new SimpleMeterRegistry();
        engine = new RedisCountingBloomEngineImpl(redisTemplate, meterRegistry);
    }

    @Test
    void buildSerializesNumericLayoutArgumentsForStringRedisTemplate() {
        CountingBloomLayout layout = new CountingBloomLayout(
                1_000_000,
                4,
                1,
                1_000_000);
        CountingBloomNamespace namespace = new CountingBloomNamespace(
                layout,
                KEY_FACTORY.apiKeyBloomMetaKey(),
                List.of(KEY_FACTORY.apiKeyBloomBucketKey(0)),
                List.of(KEY_FACTORY.apiKeyBloomReceiptKey(0)),
                KEY_FACTORY.apiKeyBloomPositiveMutationKey());
        BuildFence fence = new BuildFence(
                KEY_FACTORY.apiKeyBloomLeaderKey(),
                "1:test-build-lease",
                1L,
                Duration.ofSeconds(30));
        redisTemplate.opsForValue().set(
                fence.leaderKey(), fence.leaseValue(), fence.leaseTtl());

        engine.initializeBuilding(namespace, fence);
        long added = engine.addBuildBatch(
                namespace, List.of("protected-api-key-digest"), fence);

        assertThat(added).isEqualTo(1L);
        assertThat(redisTemplate.opsForHash().get(namespace.metaKey(), "element_count"))
                .isEqualTo("1");
    }
}
