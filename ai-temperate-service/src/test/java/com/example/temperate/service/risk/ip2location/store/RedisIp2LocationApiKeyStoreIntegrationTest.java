package com.example.temperate.service.risk.ip2location.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationImportMode;
import com.example.temperate.service.risk.ip2location.domain.ProtectedIp2LocationKey;
import com.example.temperate.service.risk.ip2location.store.impl.RedisIp2LocationApiKeyStore;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
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
 * 该测试是来使用隔离 Redis 7.4.9 验证部分接受、字段 TTL、随机领取和两个 Hash 对齐行为。
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisIp2LocationApiKeyStoreIntegrationTest {

    private static final HmacIdentifier KEY_ID =
            HmacIdentifier.fromProtectedValue(
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.9-alpine"))
                    .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisIp2LocationApiKeyStore store;
    private RedisKeyFactory keyFactory;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(),
                REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        keyFactory = new RedisKeyFactory("test");
        store = new RedisIp2LocationApiKeyStore(
                redisTemplate,
                keyFactory,
                new NetworkRiskMetrics(new SimpleMeterRegistry()));
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void quotaIsAtomicallyDecrementedAndBothFieldsDisappearAtZero() {
        Ip2LocationApiKeyStore.BatchWriteResult written = store.writeBatch(
                List.of(protectedKey(Instant.now().plusSeconds(60))),
                2,
                Ip2LocationImportMode.CREATE_ONLY);

        assertThat(written.createdCount()).isEqualTo(1);
        assertThat(store.acquire().orElseThrow().remainingQuota())
                .isEqualTo(1L);
        assertThat(store.acquire().orElseThrow().remainingQuota())
                .isEqualTo(0L);
        assertThat(redisTemplate.opsForHash().hasKey(
                        keyFactory.ip2LocationSecretHashKey(),
                        KEY_ID.value()))
                .isFalse();
        assertThat(redisTemplate.opsForHash().hasKey(
                        keyFactory.ip2LocationQuotaHashKey(),
                        KEY_ID.value()))
                .isFalse();
    }

    @Test
    void hashFieldsExpireTogetherAtTheirAbsoluteDeadline()
            throws InterruptedException {
        store.writeBatch(
                List.of(protectedKey(Instant.now().plusSeconds(2))),
                10,
                Ip2LocationImportMode.CREATE_ONLY);

        assertThat(redisTemplate.opsForHash().hasKey(
                        keyFactory.ip2LocationSecretHashKey(),
                        KEY_ID.value()))
                .isTrue();
        assertThat(redisTemplate.opsForHash().hasKey(
                        keyFactory.ip2LocationQuotaHashKey(),
                        KEY_ID.value()))
                .isTrue();

        Thread.sleep(2_500L);

        assertThat(redisTemplate.opsForHash().hasKey(
                        keyFactory.ip2LocationSecretHashKey(),
                        KEY_ID.value()))
                .isFalse();
        assertThat(redisTemplate.opsForHash().hasKey(
                        keyFactory.ip2LocationQuotaHashKey(),
                        KEY_ID.value()))
                .isFalse();
    }

    @Test
    void createOnlyDoesNotResetExistingQuotaButUpsertDoes() {
        ProtectedIp2LocationKey key =
                protectedKey(Instant.now().plusSeconds(60));
        store.writeBatch(List.of(key), 10, Ip2LocationImportMode.CREATE_ONLY);
        Ip2LocationApiKeyStore.BatchWriteResult duplicate =
                store.writeBatch(List.of(key), 99, Ip2LocationImportMode.CREATE_ONLY);

        assertThat(duplicate.duplicateCount()).isEqualTo(1);
        assertThat(store.acquire().orElseThrow().remainingQuota())
                .isEqualTo(9L);

        Ip2LocationApiKeyStore.BatchWriteResult updated =
                store.writeBatch(List.of(key), 20, Ip2LocationImportMode.UPSERT);

        assertThat(updated.updatedCount()).isEqualTo(1);
        assertThat(store.acquire().orElseThrow().remainingQuota())
                .isEqualTo(19L);
    }

    @Test
    void capacityAcceptsTheFirstRemainingSlotsAndRejectsOnlyTheTail() {
        List<ProtectedIp2LocationKey> existing = java.util.stream.IntStream.range(0, 98)
                .mapToObj(index -> protectedKey(index, Instant.now().plusSeconds(60)))
                .toList();
        store.writeBatch(existing, 10, Ip2LocationImportMode.CREATE_ONLY);
        List<ProtectedIp2LocationKey> incoming = java.util.stream.IntStream.range(98, 103)
                .mapToObj(index -> protectedKey(index, Instant.now().plusSeconds(60)))
                .toList();

        Ip2LocationApiKeyStore.BatchWriteResult result = store.writeBatch(
                incoming,
                10,
                Ip2LocationImportMode.CREATE_ONLY);

        assertThat(result.createdCount()).isEqualTo(2);
        assertThat(result.capacityRejectedCount()).isEqualTo(3);
        assertThat(redisTemplate.opsForHash().size(keyFactory.ip2LocationSecretHashKey()))
                .isEqualTo(100L);
        assertThat(redisTemplate.opsForHash().size(keyFactory.ip2LocationQuotaHashKey()))
                .isEqualTo(100L);
    }

    @Test
    void fullCapacityStillAllowsDuplicateAndUpsertForExistingCredential() {
        List<ProtectedIp2LocationKey> maximum = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> protectedKey(index, Instant.now().plusSeconds(60)))
                .toList();
        store.writeBatch(maximum, 10, Ip2LocationImportMode.CREATE_ONLY);

        Ip2LocationApiKeyStore.BatchWriteResult duplicate = store.writeBatch(
                List.of(maximum.getFirst()), 20, Ip2LocationImportMode.CREATE_ONLY);
        Ip2LocationApiKeyStore.BatchWriteResult updated = store.writeBatch(
                List.of(maximum.getFirst()), 20, Ip2LocationImportMode.UPSERT);

        assertThat(duplicate.duplicateCount()).isEqualTo(1);
        assertThat(duplicate.capacityRejectedCount()).isZero();
        assertThat(updated.updatedCount()).isEqualTo(1);
        assertThat(updated.capacityRejectedCount()).isZero();
    }

    @Test
    void retryingMultiplePipelineBatchesConvergesWithoutCreatingNewEntries() {
        List<ProtectedIp2LocationKey> keys = java.util.stream.IntStream.range(0, 75)
                .mapToObj(index -> protectedKey(index, Instant.now().plusSeconds(60)))
                .toList();

        Ip2LocationApiKeyStore.BatchWriteResult first = store.writeBatch(
                keys, 10, Ip2LocationImportMode.CREATE_ONLY);
        Ip2LocationApiKeyStore.BatchWriteResult retry = store.writeBatch(
                keys, 99, Ip2LocationImportMode.CREATE_ONLY);

        assertThat(first.createdCount()).isEqualTo(75);
        assertThat(retry.createdCount()).isZero();
        assertThat(retry.duplicateCount()).isEqualTo(75);
        assertThat(retry.capacityRejectedCount()).isZero();
        assertThat(redisTemplate.opsForHash().size(keyFactory.ip2LocationSecretHashKey()))
                .isEqualTo(75L);
        assertThat(redisTemplate.opsForHash().size(keyFactory.ip2LocationQuotaHashKey()))
                .isEqualTo(75L);
    }

    @Test
    void concurrentPipelinesNeverExceedTheGlobalCapacity() throws Exception {
        List<ProtectedIp2LocationKey> first = java.util.stream.IntStream.range(0, 75)
                .mapToObj(index -> protectedKey(index, Instant.now().plusSeconds(60)))
                .toList();
        List<ProtectedIp2LocationKey> second = java.util.stream.IntStream.range(75, 150)
                .mapToObj(index -> protectedKey(index, Instant.now().plusSeconds(60)))
                .toList();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Ip2LocationApiKeyStore.BatchWriteResult> firstResult = executor.submit(
                    () -> store.writeBatch(first, 10, Ip2LocationImportMode.CREATE_ONLY));
            Future<Ip2LocationApiKeyStore.BatchWriteResult> secondResult = executor.submit(
                    () -> store.writeBatch(second, 10, Ip2LocationImportMode.CREATE_ONLY));

            int created = firstResult.get().createdCount() + secondResult.get().createdCount();
            assertThat(created).isEqualTo(100);
            assertThat(redisTemplate.opsForHash().size(keyFactory.ip2LocationSecretHashKey()))
                    .isEqualTo(100L);
            assertThat(redisTemplate.opsForHash().size(keyFactory.ip2LocationQuotaHashKey()))
                    .isEqualTo(100L);
        } finally {
            executor.shutdownNow();
        }
    }

    private static ProtectedIp2LocationKey protectedKey(Instant expiresAt) {
        return new ProtectedIp2LocationKey(
                KEY_ID,
                "v1.test-iv.test-ciphertext",
                expiresAt);
    }

    private static ProtectedIp2LocationKey protectedKey(int index, Instant expiresAt) {
        byte[] identifier = new byte[32];
        identifier[28] = (byte) (index >>> 24);
        identifier[29] = (byte) (index >>> 16);
        identifier[30] = (byte) (index >>> 8);
        identifier[31] = (byte) index;
        return new ProtectedIp2LocationKey(
                HmacIdentifier.fromProtectedValue(
                        Base64.getUrlEncoder().withoutPadding().encodeToString(identifier)),
                "v1.test-iv.test-ciphertext-" + index,
                expiresAt);
    }
}
