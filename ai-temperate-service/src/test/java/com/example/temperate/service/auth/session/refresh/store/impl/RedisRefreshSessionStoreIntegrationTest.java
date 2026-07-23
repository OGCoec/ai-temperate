package com.example.temperate.service.auth.session.refresh.store.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.session.refresh.dto.command.NewRefreshSession;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionRevocation;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionValidation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
/**
 * 在 Redis 环境中验证刷新会话创建、续期、CSRF 轮换与撤销的原子语义。
 */
class RedisRefreshSessionStoreIntegrationTest {

    private static final String REDIS_IMAGE =
            System.getenv().getOrDefault("AIT_TEST_REDIS_IMAGE", "redis:7.4.9-alpine");
    private static final long USER_ID = 10001L;
    private static final long THREE_HOURS_MILLIS = Duration.ofHours(3).toMillis();

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);

    private static final HmacSha256Identifier HMAC = new HmacSha256Identifier(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final RedisKeyFactory KEYS = new RedisKeyFactory("test");
    private static final PublicIdCodec PUBLIC_IDS = new PublicIdCodec();

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private RedisRefreshSessionStore store;

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
        store = new RedisRefreshSessionStore(
                redisTemplate, KEYS, PUBLIC_IDS, Duration.ofHours(3), 10, 100);
    }

    @Test
    void createStoresExactlySixRtFieldsAndThreeAlignedTtls() {
        NewRefreshSession session = session("one");

        store.create(session);

        String rtKey = KEYS.sessionRefreshTokenKey(session.refreshTokenHash());
        String userIndexKey = KEYS.sessionUserIndexKey(USER_ID);
        assertThat(redisTemplate.opsForHash().keys(rtKey)).containsExactlyInAnyOrderElementsOf(
                Set.of("userId", "publicId", "csrfHash", "email", "phone", "deviceHash"));
        assertThat(redisTemplate.opsForHash().get(userIndexKey, session.refreshTokenHash().value()))
                .isEqualTo(rtKey);
        assertNearThreeHours(redisTemplate.getExpire(rtKey, TimeUnit.MILLISECONDS));
        assertNearThreeHours(hashFieldTtl(userIndexKey, session.refreshTokenHash().value()));
        assertNearThreeHours(redisTemplate.getExpire(userIndexKey, TimeUnit.MILLISECONDS));
        assertThat(rtKey).doesNotContain("A2345678901234567890123456789012345678");
    }

    @Test
    void validateRenewsOnlyCurrentFieldAndKeepsFixedRefreshHash() {
        NewRefreshSession current = session("current");
        NewRefreshSession other = session("other");
        store.create(current);
        store.create(other);
        String userIndexKey = KEYS.sessionUserIndexKey(USER_ID);
        setHashFieldTtl(userIndexKey, current.refreshTokenHash().value(), 60_000L);
        setHashFieldTtl(userIndexKey, other.refreshTokenHash().value(), 120_000L);
        redisTemplate.expire(
                KEYS.sessionRefreshTokenKey(current.refreshTokenHash()), 60, TimeUnit.SECONDS);

        RefreshSessionValidation validation = store.validateAndRenew(
                current.refreshTokenHash(), current.deviceHash(), current.csrfHash());

        assertThat(validation.status()).isEqualTo(RefreshSessionValidation.Status.VALID);
        assertNearThreeHours(hashFieldTtl(userIndexKey, current.refreshTokenHash().value()));
        assertThat(hashFieldTtl(userIndexKey, other.refreshTokenHash().value()))
                .isBetween(100_000L, 120_000L);
        assertNearThreeHours(redisTemplate.getExpire(userIndexKey, TimeUnit.MILLISECONDS));
        assertThat(redisTemplate.hasKey(
                KEYS.sessionRefreshTokenKey(current.refreshTokenHash()))).isTrue();
    }

    @Test
    void bootstrapChangesOnlyCsrfAndCurrentLogoutKeepsOtherSession() {
        NewRefreshSession current = session("current");
        NewRefreshSession other = session("other");
        store.create(current);
        store.create(other);
        HmacIdentifier newCsrf = id("bootstrap-csrf");

        RefreshSessionValidation bootstrap = store.bootstrapAndRenew(
                current.refreshTokenHash(), current.deviceHash(), newCsrf);
        assertThat(bootstrap.status()).isEqualTo(RefreshSessionValidation.Status.VALID);
        assertThat(bootstrap.session().csrfHash()).isEqualTo(newCsrf.value());
        assertThat(redisTemplate.opsForHash().get(
                KEYS.sessionRefreshTokenKey(current.refreshTokenHash()), "csrfHash"))
                .isEqualTo(newCsrf.value());

        RefreshSessionRevocation revoked = store.revoke(
                current.refreshTokenHash(), current.deviceHash(), newCsrf);
        assertThat(revoked.status()).isEqualTo(RefreshSessionRevocation.Status.REVOKED);
        assertThat(redisTemplate.hasKey(
                KEYS.sessionRefreshTokenKey(current.refreshTokenHash()))).isFalse();
        assertThat(redisTemplate.hasKey(
                KEYS.sessionRefreshTokenKey(other.refreshTokenHash()))).isTrue();
        assertThat(redisTemplate.opsForHash().keys(KEYS.sessionUserIndexKey(USER_ID)))
                .containsExactly(other.refreshTokenHash().value());
    }

    @Test
    void eleventhSessionFailsAndRevokeAllPhysicallyDeletesEveryRt() {
        for (int index = 0; index < 10; index++) {
            store.create(session("session-" + index));
        }
        assertThatThrownBy(() -> store.create(session("session-10")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("limit");

        int revoked = store.revokeAllForUser(USER_ID);

        assertThat(revoked).isEqualTo(10);
        assertThat(redisTemplate.hasKey(KEYS.sessionUserIndexKey(USER_ID))).isFalse();
        for (int index = 0; index < 10; index++) {
            assertThat(redisTemplate.hasKey(KEYS.sessionRefreshTokenKey(
                    id("refresh-session-" + index)))).isFalse();
        }
    }

    @Test
    void revokeAllAlsoDeletesLegacyV3IndexAndRefreshHashDuringMigration() {
        HmacIdentifier legacyHash = id("legacy-session");
        String legacyRtKey = KEYS.legacySessionRefreshTokenKey(legacyHash);
        String legacyIndexKey = KEYS.legacySessionUserIndexKey(USER_ID);

        redisTemplate.opsForHash().put(legacyRtKey, "userId", Long.toString(USER_ID));
        redisTemplate.opsForHash().put(legacyIndexKey, legacyHash.value(), "1");
        redisTemplate.expire(legacyRtKey, 3, TimeUnit.HOURS);
        redisTemplate.expire(legacyIndexKey, 3, TimeUnit.HOURS);
        setHashFieldTtl(legacyIndexKey, legacyHash.value(), THREE_HOURS_MILLIS);

        int revoked = store.revokeAllForUser(USER_ID);

        assertThat(revoked).isEqualTo(1);
        assertThat(redisTemplate.hasKey(legacyRtKey)).isFalse();
        assertThat(redisTemplate.hasKey(legacyIndexKey)).isFalse();
    }

    private static NewRefreshSession session(String suffix) {
        return new NewRefreshSession(
                USER_ID,
                PUBLIC_IDS.encode(USER_ID),
                id("refresh-" + suffix),
                id("device-" + suffix),
                id("csrf-" + suffix),
                "person@example.test",
                "+8613812345678");
    }

    private static HmacIdentifier id(String value) {
        return HMAC.identify(value);
    }

    private static void assertNearThreeHours(Long ttlMillis) {
        assertThat(ttlMillis).isNotNull().isBetween(
                THREE_HOURS_MILLIS - Duration.ofSeconds(10).toMillis(),
                THREE_HOURS_MILLIS);
    }

    private static long hashFieldTtl(String key, String field) {
        Object result = rawCommand("HPTTL", key, "FIELDS", "1", field);
        if (!(result instanceof List<?> values) || values.size() != 1) {
            throw new IllegalStateException("Unexpected HPTTL response: " + result);
        }
        return number(values.getFirst());
    }

    private static void setHashFieldTtl(String key, String field, long ttlMillis) {
        rawCommand("HPEXPIRE", key, Long.toString(ttlMillis), "FIELDS", "1", field);
    }

    private static Object rawCommand(String command, String... arguments) {
        return redisTemplate.execute((RedisCallback<Object>) connection -> {
            byte[][] rawArguments = new byte[arguments.length][];
            for (int index = 0; index < arguments.length; index++) {
                rawArguments[index] = arguments[index].getBytes(StandardCharsets.UTF_8);
            }
            return connection.execute(command, rawArguments);
        });
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof byte[] bytes) {
            return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
        }
        return Long.parseLong(String.valueOf(value));
    }
}
