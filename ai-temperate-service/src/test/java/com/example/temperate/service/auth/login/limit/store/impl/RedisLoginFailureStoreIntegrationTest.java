package com.example.temperate.service.auth.login.limit.store.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.login.limit.dto.LoginAttempt;
import com.example.temperate.service.auth.login.limit.dto.ProtectedLoginAttempt;
import com.example.temperate.service.auth.login.limit.enums.LoginFailureBucket;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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

@Testcontainers(disabledWithoutDocker = true)
/**
 * 在 Redis 环境中验证登录失败窗口、封禁阈值和清理操作的实际脚本语义。
 */
class RedisLoginFailureStoreIntegrationTest {

    private static final String REDIS_IMAGE =
            System.getenv().getOrDefault("AIT_TEST_REDIS_IMAGE", "redis:7.4.2-alpine");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);

    private static final RedisKeyFactory KEY_FACTORY = new RedisKeyFactory("test");
    private static final AuthSessionSecretProtector PROTECTOR =
            new AuthSessionSecretProtector(new HmacSha256Identifier(
                    "login-limit-integration-secret-0123456789"
                            .getBytes(StandardCharsets.UTF_8)));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RedisLoginFailureStore store;

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
        store = new RedisLoginFailureStore(
                redisTemplate,
                KEY_FACTORY,
                Duration.ofMinutes(5),
                5,
                Duration.ofMinutes(15));
    }

    @Test
    void deviceAllowsFiveFailuresAndBlocksTheSixth() {
        ProtectedLoginAttempt attempt = attempt(1, 1, "203.0.113.10");

        assertFirstFailuresAllowed(attempt, LoginFailureBucket.PASSWORD, 5);
        assertThat(store.recordFailure(attempt, LoginFailureBucket.PASSWORD))
                .isEqualTo(LoginLimitDecision.BLOCKED);
        assertThat(store.check(attempt, LoginFailureBucket.PASSWORD))
                .isEqualTo(LoginLimitDecision.BLOCKED);
        assertThat(redisTemplate.hasKey(KEY_FACTORY.loginBlockKey(attempt.actorHash())))
                .isTrue();
        assertThat(redisTemplate.hasKey(KEY_FACTORY.globalDeviceBlockKey(attempt.globalDeviceHash())))
                .isTrue();
    }

    @Test
    void sameDeviceAcrossDifferentSubjectsBlocksOnTheSixthFailure() {
        for (int index = 1; index <= 5; index++) {
            assertThat(store.recordFailure(
                            attempt(index, 1, "203.0.113.20"),
                            LoginFailureBucket.PASSWORD))
                    .isEqualTo(LoginLimitDecision.ALLOWED);
        }

        ProtectedLoginAttempt sixth = attempt(6, 1, "203.0.113.20");
        assertThat(store.recordFailure(sixth, LoginFailureBucket.PASSWORD))
                .isEqualTo(LoginLimitDecision.BLOCKED);
        assertThat(redisTemplate.hasKey(KEY_FACTORY.loginBlockKey(sixth.actorHash())))
                .isTrue();
        assertThat(redisTemplate.hasKey(KEY_FACTORY.globalDeviceBlockKey(sixth.globalDeviceHash())))
                .isTrue();
        assertThat(redisTemplate.hasKey(KEY_FACTORY.loginBlockKey(sixth.identifierHash())))
                .isFalse();
    }

    @Test
    void sameNetworkAcrossDifferentDevicesDoesNotParticipateInRiskControl() {
        for (int index = 1; index <= 20; index++) {
            assertThat(store.recordFailure(
                            attempt(index, index, "203.0.113.30"),
                            LoginFailureBucket.PASSWORD))
                    .isEqualTo(LoginLimitDecision.ALLOWED);
        }

        ProtectedLoginAttempt twentieth = attempt(20, 20, "203.0.113.30");
        assertThat(redisTemplate.hasKey(KEY_FACTORY.loginBlockKey(twentieth.networkHash())))
                .isFalse();
        assertThat(redisTemplate.hasKey(KEY_FACTORY.loginBlockKey(twentieth.actorHash())))
                .isFalse();
    }

    @Test
    void successfulAuthenticationClearsBothDeviceFailureBuckets() {
        ProtectedLoginAttempt attempt = attempt(1, 1, "203.0.113.40");
        assertFirstFailuresAllowed(attempt, LoginFailureBucket.PASSWORD, 4);
        assertFirstFailuresAllowed(attempt, LoginFailureBucket.CODE, 3);

        store.clearFailures(attempt);

        String passwordFailure = KEY_FACTORY.loginPasswordFailureKey(attempt.actorHash());
        String codeFailure = KEY_FACTORY.loginCodeFailureKey(attempt.actorHash());
        assertThat(redisTemplate.hasKey(passwordFailure)).isFalse();
        assertThat(redisTemplate.hasKey(codeFailure)).isFalse();
        assertThat(store.recordFailure(attempt, LoginFailureBucket.PASSWORD))
                .isEqualTo(LoginLimitDecision.ALLOWED);
    }

    @Test
    void failureWindowAndBlockTtlExpireWithoutRenewal() {
        RedisLoginFailureStore shortStore = new RedisLoginFailureStore(
                redisTemplate,
                KEY_FACTORY,
                Duration.ofMillis(250),
                5,
                Duration.ofMillis(500));
        ProtectedLoginAttempt attempt = attempt(1, 1, "203.0.113.50");

        for (int index = 0; index < 5; index++) {
            assertThat(shortStore.recordFailure(attempt, LoginFailureBucket.PASSWORD))
                    .isEqualTo(LoginLimitDecision.ALLOWED);
        }
        String passwordFailure = KEY_FACTORY.loginPasswordFailureKey(attempt.actorHash());
        Long failureTtl = redisTemplate.getExpire(passwordFailure, TimeUnit.MILLISECONDS);
        assertThat(failureTtl).isNotNull().isPositive().isLessThanOrEqualTo(250L);
        awaitCondition(
                () -> !Boolean.TRUE.equals(redisTemplate.hasKey(passwordFailure)),
                Duration.ofSeconds(2));

        for (int index = 0; index < 5; index++) {
            assertThat(shortStore.recordFailure(attempt, LoginFailureBucket.PASSWORD))
                    .isEqualTo(LoginLimitDecision.ALLOWED);
        }
        assertThat(shortStore.recordFailure(attempt, LoginFailureBucket.PASSWORD))
                .isEqualTo(LoginLimitDecision.BLOCKED);

        String deviceBlock = KEY_FACTORY.loginBlockKey(attempt.actorHash());
        String globalDeviceBlock = KEY_FACTORY.globalDeviceBlockKey(attempt.globalDeviceHash());
        Long blockTtl = redisTemplate.getExpire(deviceBlock, TimeUnit.MILLISECONDS);
        Long globalBlockTtl = redisTemplate.getExpire(globalDeviceBlock, TimeUnit.MILLISECONDS);
        assertThat(blockTtl).isNotNull().isPositive().isLessThanOrEqualTo(500L);
        assertThat(globalBlockTtl).isNotNull().isPositive().isLessThanOrEqualTo(500L);
        awaitCondition(
                () -> shortStore.check(attempt, LoginFailureBucket.PASSWORD)
                        == LoginLimitDecision.ALLOWED,
                Duration.ofSeconds(2));
    }

    private void assertFirstFailuresAllowed(
            ProtectedLoginAttempt attempt, LoginFailureBucket bucket, int count) {
        for (int index = 0; index < count; index++) {
            assertThat(store.recordFailure(attempt, bucket))
                    .isEqualTo(LoginLimitDecision.ALLOWED);
        }
    }

    private static ProtectedLoginAttempt attempt(int subjectNumber, int deviceNumber, String ip) {
        String subject = "person" + subjectNumber + "@example.test";
        String device = String.format(
                "00000000-0000-4000-a000-%012x", deviceNumber);
        return PROTECTOR.protect(new LoginAttempt(subject, device, ip));
    }

    private static void awaitCondition(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting Redis TTL expiry.", exception);
            }
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
