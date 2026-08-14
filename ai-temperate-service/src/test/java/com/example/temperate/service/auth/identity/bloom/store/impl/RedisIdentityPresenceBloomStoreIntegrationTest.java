package com.example.temperate.service.auth.identity.bloom.store.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.bloom.counting.CountingBloomLayout;
import com.example.temperate.common.bloom.counting.CountingBloomPosition;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceBloomSettings;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceKind;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceMutationResult;
import com.example.temperate.service.auth.identity.bloom.ProtectedIdentityPresenceRecord;
import com.example.temperate.service.bloom.impl.RedisVersionedCompositeCountingBloomEngineImpl;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 在隔离 Redis 中验证身份 Bloom 的构建切换、三态查询和用户 ID 幂等语义。
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisIdentityPresenceBloomStoreIntegrationTest {

    private static final String REDIS_IMAGE =
            System.getenv().getOrDefault("AIT_TEST_REDIS_IMAGE", "redis:7.4.2-alpine");
    private static final IdentityPresenceBloomSettings SETTINGS =
            new IdentityPresenceBloomSettings(
                    true, 1_000_000, 7, 1, 1_000_000, 500, 256, 100_000);
    private static final RedisKeyFactory KEY_FACTORY = new RedisKeyFactory("test");
    private static final HmacSha256Identifier HMAC =
            new HmacSha256Identifier(
                    "identity-bloom-test-secret-0123456789"
                            .getBytes(StandardCharsets.UTF_8));

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RedisIdentityPresenceBloomStore store;

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
        store = new RedisIdentityPresenceBloomStore(
                new RedisVersionedCompositeCountingBloomEngineImpl(redisTemplate),
                KEY_FACTORY,
                SETTINGS);
    }

    @Test
    void keepsQueriesUnavailableUntilReadyVersionBecomesActive() {
        HmacIdentifier email = protectedValue("email", "user@example.com");
        HmacIdentifier phone = protectedValue("phone", "+8613812345678");

        assertThat(store.tryAcquireBuildLease("lease-1", Duration.ofMinutes(5))).isTrue();
        assertThat(store.beginBuild("v1-g1")).isNull();
        assertThat(store.check(IdentityPresenceKind.EMAIL, email))
                .isEqualTo(IdentityPresenceDecision.UNAVAILABLE);
        assertThat(store.add(new ProtectedIdentityPresenceRecord(10001L, email, phone)))
                .isEqualTo(IdentityPresenceMutationResult.APPLIED);
        assertThat(store.add(new ProtectedIdentityPresenceRecord(10001L, email, phone)))
                .isEqualTo(IdentityPresenceMutationResult.ALREADY_APPLIED);

        store.markReady("v1-g1");
        store.activate("v1-g1");

        assertThat(store.check(IdentityPresenceKind.EMAIL, email))
                .isEqualTo(IdentityPresenceDecision.POSSIBLY_PRESENT);
        assertThat(store.check(IdentityPresenceKind.PHONE, phone))
                .isEqualTo(IdentityPresenceDecision.POSSIBLY_PRESENT);
        assertThat(store.check(
                        IdentityPresenceKind.EMAIL,
                        protectedValue("email", "absent@example.com")))
                .isEqualTo(IdentityPresenceDecision.DEFINITELY_ABSENT);
        ProtectedIdentityPresenceRecord record =
                new ProtectedIdentityPresenceRecord(10001L, email, phone);
        assertThat(store.remove(record))
                .isEqualTo(IdentityPresenceMutationResult.APPLIED);
        assertThat(store.remove(record))
                .isEqualTo(IdentityPresenceMutationResult.ALREADY_APPLIED);
        assertThat(store.check(IdentityPresenceKind.EMAIL, email))
                .isEqualTo(IdentityPresenceDecision.DEFINITELY_ABSENT);
    }

    @Test
    void buildLeaseAllowsOnlyOneHolderAndCanBeReleasedByItsOwner() {
        assertThat(store.tryAcquireBuildLease("lease-owner", Duration.ofMinutes(5)))
                .isTrue();
        assertThat(store.tryAcquireBuildLease("lease-contender", Duration.ofMinutes(5)))
                .isFalse();

        store.releaseBuildLease("lease-contender");
        assertThat(store.tryAcquireBuildLease("lease-contender", Duration.ofMinutes(5)))
                .isFalse();
        store.releaseBuildLease("lease-owner");
        assertThat(store.tryAcquireBuildLease("lease-contender", Duration.ofMinutes(5)))
                .isTrue();
    }

    @Test
    void switchesToCompleteNewVersionBeforeUnlinkingPreviousGeneration() {
        ProtectedIdentityPresenceRecord first = new ProtectedIdentityPresenceRecord(
                10011L,
                protectedValue("email", "first@example.com"),
                protectedValue("phone", "+8613512345678"));
        ProtectedIdentityPresenceRecord second = new ProtectedIdentityPresenceRecord(
                10012L,
                protectedValue("email", "second@example.com"),
                protectedValue("phone", "+8613612345678"));
        store.beginBuild("v1-first");
        store.add(first);
        store.markReady("v1-first");
        store.activate("v1-first");

        assertThat(store.beginBuild("v1-second")).isEqualTo("v1-first");
        assertThat(store.check(IdentityPresenceKind.EMAIL, first.protectedEmail()))
                .isEqualTo(IdentityPresenceDecision.UNAVAILABLE);
        assertThat(store.addAll(List.of(first, second)))
                .isEqualTo(IdentityPresenceMutationResult.APPLIED);
        store.markReady("v1-second");
        store.activate("v1-second");

        assertThat(store.check(IdentityPresenceKind.EMAIL, first.protectedEmail()))
                .isEqualTo(IdentityPresenceDecision.POSSIBLY_PRESENT);
        assertThat(store.check(IdentityPresenceKind.EMAIL, second.protectedEmail()))
                .isEqualTo(IdentityPresenceDecision.POSSIBLY_PRESENT);
        store.cleanupGeneration("v1-first");
        assertThat(redisTemplate.hasKey(
                        KEY_FACTORY.bucketKey("bloom", "uli-email", "v1-first", 0)))
                .isFalse();
    }

    @Test
    void overflowRejectsWholeMutationWithoutChangingOtherCounters() {
        HmacIdentifier email = protectedValue("email", "user@example.com");
        HmacIdentifier phone = protectedValue("phone", "+8613812345678");
        CountingBloomLayout layout = new CountingBloomLayout(
                1_000_000, 7, 1, 1_000_000);
        List<CountingBloomPosition> positions = layout.positions(email.value());
        CountingBloomPosition saturated = positions.get(0);
        CountingBloomPosition untouched = positions.get(1);
        String emailBucket = KEY_FACTORY.bucketKey(
                "bloom", "uli-email", "v1-g1", saturated.bucketNumber());
        store.tryAcquireBuildLease("lease-1", Duration.ofMinutes(5));
        store.beginBuild("v1-g1");
        DefaultRedisScript<Long> setByte = new DefaultRedisScript<>(
                "redis.call('SETRANGE', KEYS[1], tonumber(ARGV[1]), string.char(255)) "
                        + "return 1",
                Long.class);
        redisTemplate.execute(
                setByte,
                List.of(emailBucket),
                Integer.toString(saturated.byteOffset()));

        IdentityPresenceMutationResult result = store.add(
                new ProtectedIdentityPresenceRecord(10001L, email, phone));

        assertThat(result).isEqualTo(IdentityPresenceMutationResult.OVERFLOW);
        DefaultRedisScript<Long> readByte = new DefaultRedisScript<>(
                "local raw = redis.call('GETRANGE', KEYS[1], tonumber(ARGV[1]), tonumber(ARGV[1])) "
                        + "return (#raw == 0) and 0 or string.byte(raw)",
                Long.class);
        assertThat(redisTemplate.execute(
                        readByte,
                        List.of(emailBucket),
                        Integer.toString(untouched.byteOffset())))
                .isZero();
    }

    @Test
    void reachingConfiguredElementBoundaryAppliesMutationAndDegradesQueries() {
        IdentityPresenceBloomSettings boundarySettings =
                new IdentityPresenceBloomSettings(
                        true, 1_000_000, 7, 1, 1_000_000, 500, 256, 1);
        RedisIdentityPresenceBloomStore boundaryStore =
                new RedisIdentityPresenceBloomStore(
                        new RedisVersionedCompositeCountingBloomEngineImpl(
                                redisTemplate),
                        KEY_FACTORY,
                        boundarySettings);
        HmacIdentifier email = protectedValue("email", "boundary@example.com");
        HmacIdentifier phone = protectedValue("phone", "+8613912345678");
        boundaryStore.tryAcquireBuildLease("lease-boundary", Duration.ofMinutes(5));
        boundaryStore.beginBuild("v1-boundary");

        assertThat(boundaryStore.add(
                        new ProtectedIdentityPresenceRecord(10002L, email, phone)))
                .isEqualTo(IdentityPresenceMutationResult.CAPACITY_EXCEEDED);
        assertThat(redisTemplate.opsForHash().get(
                        KEY_FACTORY.identityPresenceBloomControlKey(), "state"))
                .isEqualTo("DEGRADED");
        assertThat(boundaryStore.check(IdentityPresenceKind.EMAIL, email))
                .isEqualTo(IdentityPresenceDecision.UNAVAILABLE);
    }

    @Test
    void underflowRejectsWholeRemovalAndKeepsReceiptForRecovery() {
        long userId = 10003L;
        HmacIdentifier email = protectedValue("email", "underflow@example.com");
        HmacIdentifier phone = protectedValue("phone", "+8613712345678");
        ProtectedIdentityPresenceRecord record =
                new ProtectedIdentityPresenceRecord(userId, email, phone);
        CountingBloomLayout layout = new CountingBloomLayout(
                1_000_000, 7, 1, 1_000_000);
        List<CountingBloomPosition> positions = layout.positions(email.value());
        CountingBloomPosition forcedZero = positions.get(0);
        CountingBloomPosition unchanged = positions.get(1);
        String emailBucket = KEY_FACTORY.bucketKey(
                "bloom", "uli-email", "v1-underflow", forcedZero.bucketNumber());
        store.tryAcquireBuildLease("lease-underflow", Duration.ofMinutes(5));
        store.beginBuild("v1-underflow");
        assertThat(store.add(record)).isEqualTo(IdentityPresenceMutationResult.APPLIED);
        DefaultRedisScript<Long> setByte = new DefaultRedisScript<>(
                "redis.call('SETRANGE', KEYS[1], tonumber(ARGV[1]), string.char(0)) "
                        + "return 1",
                Long.class);
        redisTemplate.execute(
                setByte,
                List.of(emailBucket),
                Integer.toString(forcedZero.byteOffset()));

        assertThat(store.remove(record))
                .isEqualTo(IdentityPresenceMutationResult.UNDERFLOW);
        DefaultRedisScript<Long> readByte = new DefaultRedisScript<>(
                "local raw = redis.call('GETRANGE', KEYS[1], tonumber(ARGV[1]), tonumber(ARGV[1])) "
                        + "return (#raw == 0) and 0 or string.byte(raw)",
                Long.class);
        assertThat(redisTemplate.execute(
                        readByte,
                        List.of(emailBucket),
                        Integer.toString(unchanged.byteOffset())))
                .isEqualTo(1L);
        int receiptShard = Long.hashCode(userId) & (SETTINGS.receiptShards() - 1);
        assertThat(redisTemplate.opsForSet().isMember(
                        KEY_FACTORY.identityPresenceBloomReceiptKey(
                                "v1-underflow", receiptShard),
                        Long.toString(userId)))
                .isTrue();
    }

    private static HmacIdentifier protectedValue(String kind, String value) {
        return HMAC.identify(
                "identity-presence:" + kind + ":v1",
                value.getBytes(StandardCharsets.UTF_8));
    }
}
