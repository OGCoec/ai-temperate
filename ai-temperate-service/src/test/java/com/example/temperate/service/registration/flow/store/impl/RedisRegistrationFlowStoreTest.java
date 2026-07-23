package com.example.temperate.service.registration.flow.store.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.domain.RegistrationActor;
import com.example.temperate.service.registration.flow.domain.RegistrationCompletionClaim;
import com.example.temperate.service.registration.flow.domain.RegistrationFlow;
import com.example.temperate.service.registration.flow.domain.RegistrationFlowSnapshot;
import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import com.example.temperate.service.registration.flow.store.RegistrationFlowStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Answers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 验证 Redis 注册流程存储在错误码、过期、验证和完成领取边界上的单元测试。
 */
class RedisRegistrationFlowStoreTest {

    private static final String EMAIL = "normalized@example.test";
    private static final String PHONE = "+15551234567";
    private static final Instant CREATED_AT = Instant.parse("2026-07-13T12:00:00Z");
    private static final Instant EXPIRES_AT = CREATED_AT.plusSeconds(600);
    private static final Instant ABSOLUTE_EXPIRES_AT = CREATED_AT.plusSeconds(1800);
    private static final Instant NOW = CREATED_AT.plusSeconds(90);

    private RedisHarness redis;
    private RedisKeyFactory keyFactory;
    private ProtectedRegistrationAccess access;
    private RegistrationActor actor;
    private RegistrationFlow flow;
    private RegistrationFlowStore store;
    private HmacSha256Identifier hmac;

    @BeforeEach
    void setUp() {
        redis = new RedisHarness();
        StringRedisTemplate template = mock(StringRedisTemplate.class, redis);
        keyFactory = new RedisKeyFactory("test");
        hmac = new HmacSha256Identifier(new byte[HmacSha256Identifier.MINIMUM_SECRET_BYTES]);
        access = new ProtectedRegistrationAccess(
                id("flow"),
                id("csrf"),
                id("challenge"),
                id("device"),
                id("global-device"),
                id("ip"),
                id("email-code"),
                id("phone-code"));
        actor = new RegistrationActor(id("actor"), id("global-actor"));
        flow = new RegistrationFlow(
                RegistrationFlow.CURRENT_SCHEMA_VERSION,
                EMAIL,
                PHONE,
                access,
                CREATED_AT,
                EXPIRES_AT);
        store = newStore(template, keyFactory);
    }

    @Test
    void createExecutesOneAtomicFlowAndChallengeScriptWithIdleAndAbsoluteExpiry() {
        redis.enqueue(0L);

        store.create(flow);

        ScriptCall call = redis.singleCall();
        assertThat(call.resultType()).isEqualTo(Long.class);
        assertThat(call.script())
                .contains("local IDLE_TTL_MILLIS = 600000")
                .contains("local ABSOLUTE_TTL_MILLIS = 1800000");
        assertThat(call.keys()).containsExactly(
                keyFactory.registrationFlowKey(access.flowId()),
                keyFactory.registrationChallengeKey(access.challengeId()));
        assertThat(call.arguments()).containsExactly(
                "2",
                EMAIL,
                PHONE,
                access.deviceHash().value(),
                access.ipHash().value(),
                access.flowCsrfHash().value(),
                access.challengeId().value(),
                Long.toString(CREATED_AT.toEpochMilli()),
                Long.toString(EXPIRES_AT.toEpochMilli()),
                Long.toString(ABSOLUTE_EXPIRES_AT.toEpochMilli()),
                "0",
                "0",
                "0",
                access.flowId().value(),
                "600000");
        assertThat(call.keys())
                .allSatisfy(key -> assertThat(key)
                        .doesNotContain(EMAIL)
                        .doesNotContain(PHONE));
    }

    @Test
    void createRejectsAnyFlowTtlOtherThanExactlySixHundredSeconds() {
        RegistrationFlow invalid = new RegistrationFlow(
                RegistrationFlow.CURRENT_SCHEMA_VERSION,
                EMAIL,
                PHONE,
                access,
                CREATED_AT,
                CREATED_AT.plusSeconds(599));

        assertRegistrationError(
                () -> store.create(invalid), RegistrationErrorCode.INVALID_INPUT);
        assertThat(redis.calls()).isEmpty();
    }

    @Test
    void isBlockedReadsTheLocalAndGlobalHmacDerivedBlockKeys() {
        redis.hasKeyResult = true;

        assertThat(store.isBlocked(actor)).isTrue();

        assertThat(redis.hasKeys).containsExactly(
                keyFactory.registrationBlockKey(actor.actorId()),
                keyFactory.globalDeviceBlockKey(actor.globalDeviceHash()));
    }

    @Test
    void mapsTheAtomicSixthConflictResultToARegistrationBlock() {
        redis.enqueue(0L, 0L, 0L, 0L, 0L, 1L);

        assertThat(store.recordConflict(actor, NOW)).isFalse();
        assertThat(store.recordConflict(actor, NOW)).isFalse();
        assertThat(store.recordConflict(actor, NOW)).isFalse();
        assertThat(store.recordConflict(actor, NOW)).isFalse();
        assertThat(store.recordConflict(actor, NOW)).isFalse();
        assertThat(store.recordConflict(actor, NOW)).isTrue();

        assertThat(redis.calls()).hasSize(6);
        assertThat(redis.calls()).allSatisfy(call -> {
            assertThat(call.keys()).containsExactly(
                    keyFactory.registrationConflictKey(actor.actorId()),
                    keyFactory.registrationBlockKey(actor.actorId()),
                    keyFactory.globalDeviceBlockKey(actor.globalDeviceHash()));
            assertThat(call.arguments().getFirst())
                    .isEqualTo(Long.toString(NOW.toEpochMilli()));
            assertThat(call.arguments()).containsExactly(
                    Long.toString(NOW.toEpochMilli()), "300000", "7200", "1", "1");
        });
    }

    @Test
    void getRequiredPassesEveryProtectedAccessBindingAndMapsSnapshot() {
        redis.enqueue(snapshotResult(true, false, true, false));

        RegistrationFlowSnapshot snapshot = store.getRequired(access, NOW);

        assertThat(snapshot).isEqualTo(new RegistrationFlowSnapshot(
                EMAIL,
                PHONE,
                true,
                false,
                true,
                false,
                CREATED_AT,
                EXPIRES_AT,
                ABSOLUTE_EXPIRES_AT));
        ScriptCall call = redis.singleCall();
        assertThat(call.keys()).containsExactly(
                keyFactory.registrationFlowKey(access.flowId()),
                keyFactory.registrationChallengeKey(access.challengeId()));
        assertThat(call.arguments()).containsExactlyElementsOf(accessArguments(NOW));
    }

    @ParameterizedTest
    @CsvSource({
        "1, REGISTRATION_FLOW_NOT_FOUND",
        "2, REGISTRATION_FLOW_EXPIRED",
        "3, REGISTRATION_FLOW_FORBIDDEN"
    })
    void getRequiredMapsControlledFlowErrors(
            long scriptStatus, RegistrationErrorCode expectedCode) {
        redis.enqueue(List.of(scriptStatus));

        assertRegistrationError(() -> store.getRequired(access, NOW), expectedCode);
    }

    @Test
    void markHumanVerifiedConsumesTheHmacChallengeBinding() {
        redis.enqueue(snapshotResult(true, false, false, false));

        RegistrationFlowSnapshot snapshot = store.markHumanVerified(access, NOW);

        assertThat(snapshot.humanVerified()).isTrue();
        ScriptCall call = redis.singleCall();
        assertThat(call.keys()).containsExactly(
                keyFactory.registrationFlowKey(access.flowId()),
                keyFactory.registrationChallengeKey(access.challengeId()));
        assertThat(call.arguments()).containsExactly(
                access.flowCsrfHash().value(),
                access.deviceHash().value(),
                access.ipHash().value(),
                access.challengeId().value(),
                Long.toString(NOW.toEpochMilli()),
                access.flowId().value());
    }

    @Test
    void repeatedTurnstileMarkMapsToControlledRejection() {
        redis.enqueue(List.of(4L));

        assertRegistrationError(
                () -> store.markHumanVerified(access, NOW),
                RegistrationErrorCode.TURNSTILE_REJECTED);
    }

    @Test
    void issueEmailCodeUsesEmailKeyAndChannelBoundCounters() {
        HmacIdentifier digest = id("email-digest");
        HmacIdentifier operationId = id("email-operation");
        redis.enqueue(0L);

        store.issueCode(access, VerificationChannel.EMAIL, digest, operationId, NOW);

        ScriptCall call = redis.singleCall();
        assertThat(call.keys()).containsExactly(
                keyFactory.registrationFlowKey(access.flowId()),
                keyFactory.registrationEmailCodeKey(access.emailCodeId()),
                keyFactory.registrationSendRiskKey(access.deviceHash()),
                keyFactory.registrationBlockKey(access.deviceHash()),
                keyFactory.globalDeviceBlockKey(access.globalDeviceHash()));
        assertThat(call.arguments()).containsExactly(
                access.flowCsrfHash().value(),
                access.deviceHash().value(),
                access.ipHash().value(),
                access.challengeId().value(),
                Long.toString(NOW.toEpochMilli()),
                digest.value(),
                operationId.value(),
                "300000",
                "60000",
                "5",
                "emailActualSendCount",
                "emailLastIssuedAt",
                "emailCooldownViolationCount",
                "300000",
                "7200");
    }

    @Test
    void issueSmsCodeUsesPhoneKeyAndChannelBoundCounters() {
        HmacIdentifier digest = id("sms-digest");
        HmacIdentifier operationId = id("sms-operation");
        redis.enqueue(0L);

        store.issueCode(access, VerificationChannel.SMS, digest, operationId, NOW);

        ScriptCall call = redis.singleCall();
        assertThat(call.keys()).containsExactly(
                keyFactory.registrationFlowKey(access.flowId()),
                keyFactory.registrationPhoneCodeKey(access.phoneCodeId()),
                keyFactory.registrationSendRiskKey(access.deviceHash()),
                keyFactory.registrationBlockKey(access.deviceHash()),
                keyFactory.globalDeviceBlockKey(access.globalDeviceHash()));
        assertThat(call.arguments()).endsWith(
                digest.value(),
                operationId.value(),
                "300000",
                "60000",
                "5",
                "phoneActualSendCount",
                "phoneLastIssuedAt",
                "phoneCooldownViolationCount",
                "300000",
                "7200");
    }

    @ParameterizedTest
    @CsvSource({
        "1, REGISTRATION_FLOW_NOT_FOUND",
        "2, REGISTRATION_FLOW_EXPIRED",
        "3, REGISTRATION_FLOW_FORBIDDEN",
        "4, HUMAN_VERIFICATION_REQUIRED",
        "5, VERIFICATION_COOLDOWN",
        "6, VERIFICATION_SEND_LIMIT"
    })
    void issueCodeMapsEveryScriptResult(
            long scriptStatus, RegistrationErrorCode expectedCode) {
        redis.enqueue(scriptStatus);

        assertRegistrationError(
                () -> store.issueCode(
                        access,
                        VerificationChannel.EMAIL,
                        id("digest"),
                        id("operation"),
                        NOW),
                expectedCode);
    }

    @Test
    void deliverySuccessAndFailureCompensationAreOperationBoundAndIdempotent() {
        HmacIdentifier operationId = id("operation");
        redis.enqueue(1L, 0L, 1L, 0L);

        assertThat(store.markCodeDeliverySucceeded(
                        access, VerificationChannel.EMAIL, operationId))
                .isTrue();
        assertThat(store.markCodeDeliverySucceeded(
                        access, VerificationChannel.EMAIL, operationId))
                .isFalse();
        assertThat(store.compensateCodeDeliveryFailure(
                        access, VerificationChannel.EMAIL, operationId))
                .isTrue();
        assertThat(store.compensateCodeDeliveryFailure(
                        access, VerificationChannel.EMAIL, id("wrong-operation")))
                .isFalse();

        assertThat(redis.calls().get(0).arguments()).contains(operationId.value());
        assertThat(redis.calls().get(0).keys()).containsExactly(
                keyFactory.registrationFlowKey(access.flowId()),
                keyFactory.registrationEmailCodeKey(access.emailCodeId()),
                keyFactory.registrationSendRiskKey(access.deviceHash()),
                keyFactory.registrationBlockKey(access.deviceHash()),
                keyFactory.globalDeviceBlockKey(access.globalDeviceHash()));
        assertThat(redis.calls().get(2).keys()).containsExactly(
                keyFactory.registrationFlowKey(access.flowId()),
                keyFactory.registrationEmailCodeKey(access.emailCodeId()),
                keyFactory.registrationSendRiskKey(access.deviceHash()));
        assertThat(redis.calls().get(2).arguments()).endsWith(
                operationId.value(),
                "emailVerified",
                "emailActualSendCount",
                "emailLastIssuedAt");
    }

    @Test
    void claimAndReleaseDeliveryAttemptAreBoundToOperationAndMessageId() {
        HmacIdentifier operationId = id("operation");
        redis.enqueue(1L, 1L);

        assertThat(store.claimCodeDeliveryAttempt(
                        access, VerificationChannel.EMAIL, operationId, "message-1", 2))
                .isTrue();
        assertThat(store.releaseCodeDeliveryForRetry(
                        access, VerificationChannel.EMAIL, operationId, "message-1"))
                .isTrue();

        assertThat(redis.calls().get(0).keys()).containsExactly(
                keyFactory.registrationFlowKey(access.flowId()),
                keyFactory.registrationEmailCodeKey(access.emailCodeId()));
        assertThat(redis.calls().get(0).arguments()).endsWith(
                operationId.value(), "2", "message-1");
        assertThat(redis.calls().get(1).arguments()).endsWith(
                operationId.value(), "message-1");
    }

    @Test
    void verifyCodeAtomicallyConsumesSmsCodeAndMapsTheUpdatedSnapshot() {
        HmacIdentifier digest = id("sms-digest");
        redis.enqueue(snapshotResult(true, true, true, false));

        RegistrationFlowSnapshot snapshot =
                store.verifyCode(access, VerificationChannel.SMS, digest, NOW);

        assertThat(snapshot.phoneVerified()).isTrue();
        ScriptCall call = redis.singleCall();
        assertThat(call.keys()).containsExactly(
                keyFactory.registrationFlowKey(access.flowId()),
                keyFactory.registrationPhoneCodeKey(access.phoneCodeId()));
        assertThat(call.arguments()).endsWith(digest.value(), "5", "phoneVerified");
    }

    @ParameterizedTest
    @CsvSource({
        "1, REGISTRATION_FLOW_NOT_FOUND",
        "2, REGISTRATION_FLOW_EXPIRED",
        "3, REGISTRATION_FLOW_FORBIDDEN",
        "4, HUMAN_VERIFICATION_REQUIRED",
        "5, VERIFICATION_CODE_EXPIRED",
        "6, VERIFICATION_CODE_INVALID",
        "7, VERIFICATION_CODE_ATTEMPTS_EXHAUSTED"
    })
    void verifyCodeMapsEveryScriptResult(
            long scriptStatus, RegistrationErrorCode expectedCode) {
        redis.enqueue(List.of(scriptStatus));

        assertRegistrationError(
                () -> store.verifyCode(
                        access, VerificationChannel.EMAIL, id("digest"), NOW),
                expectedCode);
    }

    @Test
    void claimCompletionReturnsTheSameClaimOnlyAfterAtomicVerificationChecks() {
        HmacIdentifier claimId = id("claim");
        redis.enqueue(snapshotResult(true, true, true, true));

        RegistrationCompletionClaim claim = store.claimCompletion(access, claimId, NOW);

        assertThat(claim.claimId()).isEqualTo(claimId);
        assertThat(claim.snapshot().completing()).isTrue();
        ScriptCall call = redis.singleCall();
        assertThat(call.keys()).containsExactly(keyFactory.registrationFlowKey(access.flowId()));
        assertThat(call.arguments()).endsWith(
                Long.toString(NOW.toEpochMilli()), claimId.value());
    }

    @ParameterizedTest
    @CsvSource({
        "1, REGISTRATION_FLOW_NOT_FOUND",
        "2, REGISTRATION_FLOW_EXPIRED",
        "3, REGISTRATION_FLOW_FORBIDDEN",
        "4, HUMAN_VERIFICATION_REQUIRED",
        "5, EMAIL_VERIFICATION_REQUIRED",
        "6, PHONE_VERIFICATION_REQUIRED",
        "7, REGISTRATION_ALREADY_COMPLETING"
    })
    void claimCompletionMapsEveryScriptResult(
            long scriptStatus, RegistrationErrorCode expectedCode) {
        redis.enqueue(List.of(scriptStatus));

        assertRegistrationError(
                () -> store.claimCompletion(access, id("claim"), NOW),
                expectedCode);
    }

    @Test
    void releaseIsANoOpUnlessTheStoredClaimMatches() {
        HmacIdentifier claimId = id("claim");
        redis.enqueue(0L);

        assertThatCode(() -> store.releaseCompletionClaim(access, claimId))
                .doesNotThrowAnyException();

        ScriptCall call = redis.singleCall();
        assertThat(call.arguments()).endsWith(claimId.value());
    }

    @Test
    void releaseRejectsMismatchedFlowCredentials() {
        redis.enqueue(3L);

        assertRegistrationError(
                () -> store.releaseCompletionClaim(access, id("claim")),
                RegistrationErrorCode.REGISTRATION_FLOW_FORBIDDEN);
    }

    @Test
    void deleteExecutesOneScriptWithAllFlowAndRiskKeys() {
        redis.enqueue(1L);

        store.delete(access);

        ScriptCall call = redis.singleCall();
        assertThat(call.script())
                .contains("redis.call('UNLINK', KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6])");
        assertThat(call.keys()).containsExactly(
                keyFactory.registrationFlowKey(access.flowId()),
                keyFactory.registrationEmailCodeKey(access.emailCodeId()),
                keyFactory.registrationPhoneCodeKey(access.phoneCodeId()),
                keyFactory.registrationChallengeKey(access.challengeId()),
                keyFactory.registrationSendRiskKey(access.deviceHash()),
                keyFactory.registrationVerifyRiskKey(access.deviceHash()));
    }

    @Test
    void deleteRejectsMismatchedFlowCredentials() {
        redis.enqueue(3L);

        assertRegistrationError(
                () -> store.delete(access),
                RegistrationErrorCode.REGISTRATION_FLOW_FORBIDDEN);
    }

    @Test
    void unexpectedScriptStatusMapsToRegistrationUnavailable() {
        redis.enqueue(List.of(99L));

        assertRegistrationError(
                () -> store.getRequired(access, NOW),
                RegistrationErrorCode.AUTH_REGISTER_UNAVAILABLE);
    }

    @Test
    void constructorRejectsConfigurationThatChangesFixedSecurityBoundaries() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);

        assertThatThrownBy(() -> newStore(
                        template,
                        keyFactory,
                        Duration.ofMinutes(5),
                        Duration.ofHours(2),
                        Duration.ofMinutes(5),
                        Duration.ofSeconds(59),
                        5,
                        5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("send cooldown");
    }

    private HmacIdentifier id(String value) {
        return hmac.identify("redis-flow-test:" + value);
    }

    private List<Object> snapshotResult(
            boolean humanVerified,
            boolean emailVerified,
            boolean phoneVerified,
            boolean completing) {
        return List.of(
                0L,
                EMAIL,
                PHONE,
                bit(humanVerified),
                bit(emailVerified),
                bit(phoneVerified),
                bit(completing),
                Long.toString(CREATED_AT.toEpochMilli()),
                Long.toString(EXPIRES_AT.toEpochMilli()),
                Long.toString(ABSOLUTE_EXPIRES_AT.toEpochMilli()));
    }

    private List<Object> accessArguments(Instant now) {
        return List.of(
                access.flowCsrfHash().value(),
                access.deviceHash().value(),
                access.ipHash().value(),
                access.challengeId().value(),
                Long.toString(now.toEpochMilli()),
                "600000");
    }

    private static String bit(boolean value) {
        return value ? "1" : "0";
    }

    private static void assertRegistrationError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            RegistrationErrorCode expectedCode) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(
                        RegistrationException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expectedCode));
    }

    private static RegistrationFlowStore newStore(
            StringRedisTemplate template, RedisKeyFactory keyFactory) {
        return newStore(
                template,
                keyFactory,
                Duration.ofMinutes(5),
                Duration.ofHours(2),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                5,
                5);
    }

    private static RegistrationFlowStore newStore(
            StringRedisTemplate template,
            RedisKeyFactory keyFactory,
            Duration conflictWindow,
            Duration conflictBlockDuration,
            Duration codeTtl,
            Duration sendCooldown,
            int maxSendsPerChannel,
            int maxCodeAttempts) {
        try {
            Class<?> type = Class.forName(
                    "com.example.temperate.service.registration.flow.store.impl.RedisRegistrationFlowStore");
            Constructor<?> constructor = type.getConstructor(
                    StringRedisTemplate.class,
                    RedisKeyFactory.class,
                    Duration.class,
                    Duration.class,
                    Duration.class,
                    Duration.class,
                    int.class,
                    int.class);
            return (RegistrationFlowStore) constructor.newInstance(
                    template,
                    keyFactory,
                    conflictWindow,
                    conflictBlockDuration,
                    codeTtl,
                    sendCooldown,
                    maxSendsPerChannel,
                    maxCodeAttempts);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(
                    "Redis registration flow store construction failed.", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Redis registration flow store implementation is missing.",
                    exception);
        }
    }

    private record ScriptCall(
            String script,
            Class<?> resultType,
            List<String> keys,
            List<Object> arguments) {
    }

    private static final class RedisHarness implements Answer<Object> {

        private final Deque<Object> results = new ArrayDeque<>();
        private final List<ScriptCall> calls = new ArrayList<>();
        private boolean hasKeyResult;
        private final List<String> hasKeys = new ArrayList<>();

        void enqueue(Object... values) {
            results.addAll(Arrays.asList(values));
        }

        List<ScriptCall> calls() {
            return List.copyOf(calls);
        }

        ScriptCall singleCall() {
            assertThat(calls).hasSize(1);
            return calls.getFirst();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object answer(InvocationOnMock invocation) throws Throwable {
            Object[] raw = invocation.getRawArguments();
            if ("execute".equals(invocation.getMethod().getName())
                    && raw.length >= 3
                    && raw[0] instanceof RedisScript<?> script) {
                Object[] arguments = raw[2] instanceof Object[] values
                        ? values.clone()
                        : Arrays.copyOfRange(raw, 2, raw.length);
                calls.add(new ScriptCall(
                        script.getScriptAsString(),
                        script.getResultType(),
                        List.copyOf((List<String>) raw[1]),
                        List.copyOf(Arrays.asList(arguments))));
                if (results.isEmpty()) {
                    throw new AssertionError("No Redis script result was queued for the test.");
                }
                return results.removeFirst();
            }
            if ("hasKey".equals(invocation.getMethod().getName())) {
                hasKeys.add((String) raw[0]);
                return hasKeyResult;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        }
    }
}
