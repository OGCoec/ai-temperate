package com.example.temperate.service.risk.ip2location.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationImportMode;
import com.example.temperate.service.risk.ip2location.domain.ProtectedIp2LocationKey;
import com.example.temperate.service.risk.ip2location.exception.Ip2LocationApiKeyCapacityExceededException;
import com.example.temperate.service.risk.ip2location.store.impl.RedisIp2LocationApiKeyStore;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
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
 * 使用隔离 Redis 7.4.9 验证两个 Hash 的字段 TTL、随机领取和额度归零删除原子行为。
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
        store = new RedisIp2LocationApiKeyStore(redisTemplate, keyFactory);
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
    void globalCapacityRejectsTheOneHundredFirstDistinctKeyWithoutPartialWrite() {
        List<ProtectedIp2LocationKey> maximum = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> protectedKey(index, Instant.now().plusSeconds(60)))
                .toList();
        store.writeBatch(maximum, 10, Ip2LocationImportMode.CREATE_ONLY);

        assertThatThrownBy(() -> store.writeBatch(
                        List.of(protectedKey(100, Instant.now().plusSeconds(60))),
                        10,
                        Ip2LocationImportMode.CREATE_ONLY))
                .isInstanceOf(Ip2LocationApiKeyCapacityExceededException.class);
        assertThat(redisTemplate.opsForHash().size(keyFactory.ip2LocationSecretHashKey()))
                .isEqualTo(100L);
        assertThat(redisTemplate.opsForHash().size(keyFactory.ip2LocationQuotaHashKey()))
                .isEqualTo(100L);
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
