package com.example.temperate.service.registration.flow.store.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.domain.RegistrationActor;
import com.example.temperate.service.registration.flow.domain.RegistrationCompletionClaim;
import com.example.temperate.service.registration.flow.domain.RegistrationFlow;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 在真实 Redis 容器中验证注册流程 Lua 状态机原子行为的集成测试。
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisRegistrationFlowStoreIntegrationTest {

    private static final String REDIS_IMAGE =
            System.getenv().getOrDefault("AIT_TEST_REDIS_IMAGE", "redis:7.4.2-alpine");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);

    private static final HmacSha256Identifier HMAC = new HmacSha256Identifier(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final RedisKeyFactory KEY_FACTORY = new RedisKeyFactory("test");

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private RedisRegistrationFlowStore store;

    @BeforeAll
    static void connectToRedis() {
        connectionFactory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
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
        store = new RedisRegistrationFlowStore(
                redisTemplate,
                KEY_FACTORY,
                Duration.ofMinutes(5),
                Duration.ofHours(2),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                5,
                5);
    }

    @Test
    void sixthConflictCreatesATwoHourBlockAndKeepsTheWindowFixed() {
        RegistrationActor actor = new RegistrationActor(
                id("actor-" + UUID.randomUUID()),
                id("global-actor-" + UUID.randomUUID()));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(store.recordConflict(actor, true, true, now)).isFalse();
        }
        String conflictKey = KEY_FACTORY.registrationConflictKey(actor.actorId());
        Long ttlBefore = redisTemplate.getExpire(conflictKey, TimeUnit.MILLISECONDS);
        assertThat(store.recordConflict(actor, true, false, now.plusSeconds(1))).isTrue();

        assertThat(store.isBlocked(actor)).isTrue();
        assertThat(redisTemplate.hasKey(conflictKey)).isFalse();
        assertThat(ttlBefore).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(5).toMillis());
        Long blockTtl = redisTemplate.getExpire(
                KEY_FACTORY.registrationBlockKey(actor.actorId()), TimeUnit.SECONDS);
        assertThat(blockTtl).isBetween(7190L, 7200L);
        Long globalBlockTtl = redisTemplate.getExpire(
                KEY_FACTORY.globalDeviceBlockKey(actor.globalDeviceHash()), TimeUnit.SECONDS);
        assertThat(globalBlockTtl).isBetween(7190L, 7200L);
    }

    @Test
    void flowOperationsRenewIdleTtlWithoutPassingTheThirtyMinuteAbsoluteDeadline() {
        TestFlow testFlow = createFlow();
        String flowKey = KEY_FACTORY.registrationFlowKey(testFlow.access().flowId());
        assertThat(redisTemplate.expire(flowKey, Duration.ofSeconds(2))).isTrue();

        store.getRequired(testFlow.access(), testFlow.now());

        Long remainingMillis = redisTemplate.getExpire(flowKey, TimeUnit.MILLISECONDS);
        assertThat(remainingMillis).isGreaterThan(Duration.ofMinutes(9).toMillis());

        redisTemplate.opsForHash().put(
                flowKey,
                "absoluteExpiresAt",
                Long.toString(testFlow.now().plusSeconds(30).toEpochMilli()));
        store.getRequired(testFlow.access(), testFlow.now());
        assertThat(redisTemplate.getExpire(flowKey, TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(30_000L);
    }

    @Test
    void combinedVerificationIsAllOrNothingAndTheEleventhFailureBlocksTheDevice() {
        TestFlow exhausted = createHumanVerifiedFlow();
        HmacIdentifier email = id("email-" + UUID.randomUUID());
        HmacIdentifier phone = id("phone-" + UUID.randomUUID());
        HmacIdentifier wrong = id("wrong-" + UUID.randomUUID());
        issueDelivered(exhausted, VerificationChannel.EMAIL, email);
        issueDelivered(exhausted, VerificationChannel.SMS, phone);

        for (int attempt = 1; attempt <= 10; attempt++) {
            RegistrationErrorCode expected = attempt % 5 == 0
                    ? RegistrationErrorCode.VERIFICATION_CODE_ATTEMPTS_EXHAUSTED
                    : RegistrationErrorCode.VERIFICATION_CODE_INVALID;
            assertRegistrationError(
                    () -> store.verifyCodes(
                            exhausted.access(), wrong, wrong, exhausted.now()),
                    expected);
            if (attempt == 5) {
                TestFlow afterCooldown = new TestFlow(
                        exhausted.access(), exhausted.now().plusSeconds(61));
                issueDelivered(afterCooldown, VerificationChannel.EMAIL, email);
                issueDelivered(afterCooldown, VerificationChannel.SMS, phone);
            }
        }
        assertRegistrationError(
                () -> store.verifyCodes(exhausted.access(), wrong, wrong, exhausted.now()),
                RegistrationErrorCode.AUTH_REGISTER_UNAVAILABLE);
        assertThat(store.isBlocked(new RegistrationActor(
                        exhausted.access().deviceHash(),
                        exhausted.access().globalDeviceHash())))
                .isTrue();

        TestFlow consumed = createHumanVerifiedFlow();
        HmacIdentifier goodEmail = id("good-email-" + UUID.randomUUID());
        HmacIdentifier goodPhone = id("good-phone-" + UUID.randomUUID());
        issueDelivered(consumed, VerificationChannel.EMAIL, goodEmail);
        issueDelivered(consumed, VerificationChannel.SMS, goodPhone);
        assertRegistrationError(
                () -> store.verifyCodes(
                        consumed.access(), goodEmail, wrong, consumed.now()),
                RegistrationErrorCode.VERIFICATION_CODE_INVALID);
        assertThat(store.getRequired(consumed.access(), consumed.now()).emailVerified()).isFalse();
        assertThat(store.verifyCodes(
                        consumed.access(), goodEmail, goodPhone, consumed.now()).readyToComplete())
                .isTrue();
    }

    @Test
    void deliveryCompensationIsCurrentOperationBoundIdempotentAndDoesNotRenewFlowTtl() {
        TestFlow flow = createHumanVerifiedFlow();
        HmacIdentifier digest = id("delivery-digest-" + UUID.randomUUID());
        HmacIdentifier operationId = id("delivery-operation-" + UUID.randomUUID());
        HmacIdentifier wrongOperationId = id("wrong-operation-" + UUID.randomUUID());
        store.issueCode(
                flow.access(),
                VerificationChannel.EMAIL,
                digest,
                operationId,
                flow.now());
        String flowKey = KEY_FACTORY.registrationFlowKey(flow.access().flowId());
        String codeKey = KEY_FACTORY.registrationEmailCodeKey(flow.access().emailCodeId());
        Long ttlBefore = redisTemplate.getExpire(flowKey, TimeUnit.MILLISECONDS);

        assertThat(store.compensateCodeDeliveryFailure(
                        flow.access(), VerificationChannel.EMAIL, wrongOperationId))
                .isFalse();
        assertThat(redisTemplate.hasKey(codeKey)).isTrue();
        assertThat(store.compensateCodeDeliveryFailure(
                        flow.access(), VerificationChannel.EMAIL, operationId))
                .isTrue();
        assertThat(store.compensateCodeDeliveryFailure(
                        flow.access(), VerificationChannel.EMAIL, operationId))
                .isFalse();

        assertThat(redisTemplate.hasKey(codeKey)).isFalse();
        String riskKey = KEY_FACTORY.registrationSendRiskKey(flow.access().deviceHash());
        assertThat(redisTemplate.opsForHash().get(riskKey, "emailActualSendCount"))
                .isNull();
        assertThat(redisTemplate.opsForHash().get(riskKey, "emailLastIssuedAt"))
                .isNull();
        assertThat(redisTemplate.getExpire(flowKey, TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(ttlBefore);
    }

    @Test
    void onlyOneConcurrentCompletionClaimWins() throws Exception {
        TestFlow ready = createReadyFlow();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyToStart = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> first = executor.submit(() -> claim(
                    ready, id("claim-first-" + UUID.randomUUID()), readyToStart, start));
            Future<Object> second = executor.submit(() -> claim(
                    ready, id("claim-second-" + UUID.randomUUID()), readyToStart, start));
            assertThat(readyToStart.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().filter(RegistrationCompletionClaim.class::isInstance))
                    .hasSize(1);
            assertThat(results).containsExactlyInAnyOrder(
                    results.stream()
                            .filter(RegistrationCompletionClaim.class::isInstance)
                            .findFirst()
                            .orElseThrow(),
                    RegistrationErrorCode.REGISTRATION_ALREADY_COMPLETING);
        } finally {
            executor.shutdownNow();
        }
    }

    private Object claim(
            TestFlow flow,
            HmacIdentifier claimId,
            CountDownLatch readyToStart,
            CountDownLatch start)
            throws InterruptedException {
        readyToStart.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent claim start signal timed out.");
        }
        try {
            return store.claimCompletion(flow.access(), claimId, flow.now());
        } catch (RegistrationException exception) {
            return exception.code();
        }
    }

    private TestFlow createReadyFlow() {
        TestFlow flow = createHumanVerifiedFlow();
        HmacIdentifier email = id("email-" + UUID.randomUUID());
        HmacIdentifier phone = id("phone-" + UUID.randomUUID());
        issueDelivered(flow, VerificationChannel.EMAIL, email);
        issueDelivered(flow, VerificationChannel.SMS, phone);
        store.verifyCodes(flow.access(), email, phone, flow.now());
        return flow;
    }

    private void issueDelivered(
            TestFlow flow, VerificationChannel channel, HmacIdentifier digest) {
        HmacIdentifier operation = id("operation-" + UUID.randomUUID());
        store.issueCode(flow.access(), channel, digest, operation, flow.now());
        assertThat(store.markCodeDeliverySucceeded(flow.access(), channel, operation)).isTrue();
    }

    private TestFlow createHumanVerifiedFlow() {
        TestFlow flow = createFlow();
        store.markHumanVerified(flow.access(), flow.now());
        return flow;
    }

    private TestFlow createFlow() {
        String suffix = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ProtectedRegistrationAccess access = new ProtectedRegistrationAccess(
                id("flow-" + suffix),
                id("csrf-" + suffix),
                id("challenge-" + suffix),
                id("device-" + suffix),
                id("global-device-" + suffix),
                id("ip-" + suffix),
                id("email-code-" + suffix),
                id("phone-code-" + suffix));
        store.create(new RegistrationFlow(
                RegistrationFlow.CURRENT_SCHEMA_VERSION,
                "redis-integration@example.test",
                "+13125550100",
                access,
                now,
                now.plusSeconds(600),
                now.plusSeconds(1800)));
        return new TestFlow(access, now);
    }

    private static HmacIdentifier id(String value) {
        return HMAC.identify("redis-integration:" + value);
    }

    private static void assertRegistrationError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            RegistrationErrorCode expectedCode) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        RegistrationException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expectedCode));
    }

    private record TestFlow(ProtectedRegistrationAccess access, Instant now) {}
}
