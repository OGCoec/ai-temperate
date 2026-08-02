package com.example.temperate.service.user.aiconversation.generation.observer.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 使用两个独立 Redis 客户端模拟应用实例 A/B，验证实时通知丢失后可由同一 Generation Hash 快照恢复。
 */
@Testcontainers(disabledWithoutDocker = true)
final class RedisAiConversationGenerationMultiInstanceIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse(System.getenv().getOrDefault(
                    "AIT_TEST_REDIS_IMAGE", "redis:7.4.9-alpine")))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactoryA;
    private static LettuceConnectionFactory connectionFactoryB;
    private static RedisMessageListenerContainer listenerContainerB;
    private static StringRedisTemplate redisA;
    private static StringRedisTemplate redisB;

    private RedisKeyFactory keyFactory;
    private RedisAiConversationGenerationOutputStoreImpl storeA;
    private RedisAiConversationGenerationOutputStoreImpl storeB;
    private RedisAiConversationGenerationOutputSubscriberImpl subscriberB;
    private String generationPublicId;

    @BeforeAll
    static void connectTwoInstances() {
        connectionFactoryA = connectionFactory();
        connectionFactoryB = connectionFactory();
        redisA = template(connectionFactoryA);
        redisB = template(connectionFactoryB);
        listenerContainerB = new RedisMessageListenerContainer();
        listenerContainerB.setConnectionFactory(connectionFactoryB);
        listenerContainerB.afterPropertiesSet();
        listenerContainerB.start();
    }

    @AfterAll
    static void disconnectTwoInstances() throws Exception {
        if (listenerContainerB != null) {
            listenerContainerB.stop();
            listenerContainerB.destroy();
        }
        if (connectionFactoryB != null) {
            connectionFactoryB.destroy();
        }
        if (connectionFactoryA != null) {
            connectionFactoryA.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        if (!listenerContainerB.isRunning()) {
            listenerContainerB.start();
        }
        try (RedisConnection connection = connectionFactoryA.getConnection()) {
            connection.serverCommands().flushAll();
        }
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        keyFactory = new RedisKeyFactory("test");
        storeA = new RedisAiConversationGenerationOutputStoreImpl(
                redisA, keyFactory, properties("instance-a"), objectMapper);
        storeB = new RedisAiConversationGenerationOutputStoreImpl(
                redisB, keyFactory, properties("instance-b"), objectMapper);
        subscriberB = new RedisAiConversationGenerationOutputSubscriberImpl(
                listenerContainerB, keyFactory, objectMapper);
        subscriberB.registerTopic();
        byte[] generationId = new byte[16];
        generationId[15] = 17;
        generationPublicId = new HybridBase64UrlCodec().encode(generationId);
    }

    @Test
    void instanceBReceivesInstanceARevisionInRealTime() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        List<AiConversationGenerationOutputEvent> events = new CopyOnWriteArrayList<>();
        try (AutoCloseable ignored = subscriberB.subscribe(generationPublicId, event -> {
            events.add(event);
            received.countDown();
        })) {
            storeA.appendDelta(generationPublicId, "第一段");

            assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.generationPublicId()).isEqualTo(generationPublicId);
                assertThat(event.revision()).isEqualTo(1L);
                assertThat(event.eventName()).isEqualTo("delta");
                assertThat(event.dataJson()).contains("第一段");
            });
        }
    }

    @Test
    void missedPubSubRevisionIsRecoveredFromSingleHashSnapshotWithoutDuplicates()
            throws Exception {
        CountDownLatch firstReceived = new CountDownLatch(1);
        List<Long> liveRevisions = new CopyOnWriteArrayList<>();
        try (AutoCloseable ignored = subscriberB.subscribe(generationPublicId, event -> {
            liveRevisions.add(event.revision());
            firstReceived.countDown();
        })) {
            storeA.appendDelta(generationPublicId, "第一段");
            assertThat(firstReceived.await(3, TimeUnit.SECONDS)).isTrue();

            // B 暂停订阅期间故意丢失第二个通知，恢复依据只能来自共享 Hash 的 revision 与快照。
            listenerContainerB.stop();
            storeA.appendDelta(generationPublicId, "第二段");
            var snapshot = storeB.snapshot(generationPublicId);

            assertThat(snapshot.revision()).isEqualTo(2L);
            assertThat(snapshot.assistantText()).isEqualTo("第一段第二段");
            assertThat(liveRevisions).containsExactly(1L);
        } finally {
            listenerContainerB.start();
        }
    }

    @Test
    void malformedPubSubMessageIsIgnoredAndCannotCreateTerminalEvent() throws Exception {
        CountDownLatch validReceived = new CountDownLatch(1);
        List<AiConversationGenerationOutputEvent> events = new CopyOnWriteArrayList<>();
        try (AutoCloseable ignored = subscriberB.subscribe(generationPublicId, event -> {
            events.add(event);
            validReceived.countDown();
        })) {
            redisA.convertAndSend(
                    keyFactory.aiConversationGenerationEventsChannel(),
                    "{broken-json");
            storeA.appendDelta(generationPublicId, "有效片段");

            assertThat(validReceived.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.eventName()).isEqualTo("delta");
                assertThat(event.revision()).isEqualTo(1L);
            });
            assertThat(storeB.snapshot(generationPublicId).terminalEventName()).isNull();
        }
    }

    private static LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static StringRedisTemplate template(LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private static AiConversationAsyncGenerationProperties properties(String instanceId) {
        return new AiConversationAsyncGenerationProperties(
                true,
                instanceId,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(24),
                2,
                Duration.ofMinutes(15));
    }
}
